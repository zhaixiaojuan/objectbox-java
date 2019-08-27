/*
 * Copyright 2017 ObjectBox Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.objectbox.relation;

import java.io.Serializable;
import java.lang.reflect.Field;

import javax.annotation.Nullable;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.Cursor;
import io.objectbox.annotation.apihint.Internal;
import io.objectbox.exception.DbDetachedException;
import io.objectbox.internal.ReflectionCache;

/**
 * Manages a to-one relation: resolves the target object, keeps the target Id in sync, etc.
 * A to-relation is unidirectional: it points from the source entity to the target entity.
 * The target is referenced by its ID, which is persisted in the source entity.
 * <p>
 * If their is a backlink {@link ToMany} relation based on this to-one relation,
 * the ToMany object will not be notified/updated about changes done here (use {@link ToMany#reset()} if required).
 */
// TODO add more tests
// TODO not exactly thread safe
// TODO enforce not-null (not zero) checks on the target setters once we use some not-null annotation
public class ToOne<TARGET> implements Serializable {
    private static final long serialVersionUID = 5092547044335989281L;

    private final Object entity;
    private final RelationInfo relationInfo;
    private final boolean virtualProperty;

    transient private BoxStore boxStore;
    transient private Box entityBox;
    transient private volatile Box<TARGET> targetBox;
    transient private Field targetIdField;

    /**
     * Resolved target entity is cached
     */
    private TARGET target;

    private long targetId;

    private volatile long resolvedTargetId;

    /** To avoid calls to {@link #getTargetId()}, which may involve expensive reflection. */
    private boolean checkIdOfTargetForPut;
    private boolean debugRelations;

    /**
     * In Java, the constructor call is generated by the ObjectBox plugin.
     *
     * @param sourceEntity The source entity that owns the to-one relation.
     * @param relationInfo Meta info as generated in the Entity_ (entity name plus underscore) classes.
     */
    public ToOne(Object sourceEntity, RelationInfo relationInfo) {
        if (sourceEntity == null) {
            throw new IllegalArgumentException("No source entity given (null)");
        }
        if (relationInfo == null) {
            throw new IllegalArgumentException("No relation info given (null)");
        }
        this.entity = sourceEntity;
        this.relationInfo = relationInfo;
        virtualProperty = relationInfo.targetIdProperty.isVirtual;
    }

    /**
     * @return The target entity of the to-one relation.
     */
    public TARGET getTarget() {
        return getTarget(getTargetId());
    }

    /** If property backed, entities can pass the target ID to avoid reflection. */
    @Internal
    public TARGET getTarget(long targetId) {
        synchronized (this) {
            if (resolvedTargetId == targetId) {
                return target;
            }
        }

        ensureBoxes(null);
        // Do not synchronize while doing DB stuff
        TARGET targetNew = targetBox.get(targetId);

        setResolvedTarget(targetNew, targetId);
        return targetNew;
    }

    private void ensureBoxes(@Nullable TARGET target) {
        // Only check the property set last
        if (targetBox == null) {
            Field boxStoreField = ReflectionCache.getInstance().getField(entity.getClass(), "__boxStore");
            try {
                boxStore = (BoxStore) boxStoreField.get(entity);
                if (boxStore == null) {
                    if (target != null) {
                        boxStoreField = ReflectionCache.getInstance().getField(target.getClass(), "__boxStore");
                        boxStore = (BoxStore) boxStoreField.get(target);
                    }
                    if (boxStore == null) {
                        throw new DbDetachedException("Cannot resolve relation for detached entities, " +
                                "call box.attach(entity) beforehand.");
                    }
                }
                debugRelations = boxStore.isDebugRelations();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            entityBox = boxStore.boxFor(relationInfo.sourceInfo.getEntityClass());
            //noinspection unchecked
            targetBox = boxStore.boxFor(relationInfo.targetInfo.getEntityClass());
        }
    }

    public TARGET getCachedTarget() {
        return target;
    }

    public boolean isResolved() {
        return resolvedTargetId == getTargetId();
    }

    public boolean isResolvedAndNotNull() {
        return resolvedTargetId != 0 && resolvedTargetId == getTargetId();
    }

    public boolean isNull() {
        return getTargetId() == 0 && target == null;
    }

    public void setTargetId(long targetId) {
        if (virtualProperty) {
            this.targetId = targetId;
        } else {
            try {
                getTargetIdField().set(entity, targetId);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Could not update to-one ID in entity", e);
            }
        }
        if (targetId != 0) {
            checkIdOfTargetForPut = false;
        }
    }

    // To do a more efficient put with only one property changed.
    void setAndUpdateTargetId(long targetId) {
        setTargetId(targetId);
        ensureBoxes(null);
        // TODO update on targetId in DB
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Sets the relation ID in the enclosed entity to the ID of the given target entity.
     * If the target entity was not put in the DB yet (its ID is 0), it will be put before to get its ID.
     */
    // TODO provide a overload with a ToMany parameter, which also gets updated
    public void setTarget(@Nullable final TARGET target) {
        if (target != null) {
            long targetId = relationInfo.targetInfo.getIdGetter().getId(target);
            checkIdOfTargetForPut = targetId == 0;
            setTargetId(targetId);
            setResolvedTarget(target, targetId);
        } else {
            setTargetId(0);
            clearResolved();
        }
    }

    /**
     * Sets the relation ID in the enclosed entity to the ID of the given target entity and puts the enclosed entity.
     * If the target entity was not put in the DB yet (its ID is 0), it will be put before to get its ID.
     */
    // TODO provide a overload with a ToMany parameter, which also gets updated
    public void setAndPutTarget(@Nullable final TARGET target) {
        ensureBoxes(target);
        if (target != null) {
            long targetId = targetBox.getId(target);
            if (targetId == 0) {
                setAndPutTargetAlways(target);
            } else {
                setTargetId(targetId);
                setResolvedTarget(target, targetId);
                entityBox.put(entity);
            }
        } else {
            setTargetId(0);
            clearResolved();
            entityBox.put(entity);
        }
    }

    /**
     * Sets the relation ID in the enclosed entity to the ID of the given target entity and puts both entities.
     */
    // TODO provide a overload with a ToMany parameter, which also gets updated
    public void setAndPutTargetAlways(@Nullable final TARGET target) {
        ensureBoxes(target);
        if (target != null) {
            boxStore.runInTx(new Runnable() {
                @Override
                public void run() {
                    long targetKey = targetBox.put(target);
                    setResolvedTarget(target, targetKey);
                    entityBox.put(entity);
                }
            });
        } else {
            setTargetId(0);
            clearResolved();
            entityBox.put(entity);
        }
    }

    /** Both values should be set (and read) "atomically" using synchronized. */
    private synchronized void setResolvedTarget(@Nullable TARGET target, long targetId) {
        if (debugRelations) {
            System.out.println("Setting resolved ToOne target to " + (target == null ? "null" : "non-null") +
                    " for ID " + targetId);
        }
        resolvedTargetId = targetId;
        this.target = target;
    }

    /**
     * Clears the target.
     */
    private synchronized void clearResolved() {
        resolvedTargetId = 0;
        target = null;
    }

    public long getTargetId() {
        if (virtualProperty) {
            return targetId;
        } else {
            // Future alternative: Implemented by generated ToOne sub classes to avoid reflection
            Field keyField = getTargetIdField();
            try {
                Long key = (Long) keyField.get(entity);
                return key != null ? key : 0;
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Could not access field " + keyField);
            }
        }
    }

    private Field getTargetIdField() {
        if (targetIdField == null) {
            targetIdField = ReflectionCache.getInstance().getField(entity.getClass(), relationInfo.targetIdProperty.name);
        }
        return targetIdField;
    }


    @Internal
    public boolean internalRequiresPutTarget() {
        return checkIdOfTargetForPut && target != null && getTargetId() == 0;
    }

    @Internal
    public void internalPutTarget(Cursor<TARGET> targetCursor) {
        checkIdOfTargetForPut = false;
        long id = targetCursor.put(target);
        setTargetId(id);
        setResolvedTarget(target, id);
    }

    /** For tests */
    Object getEntity() {
        return entity;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ToOne)) return false;
        ToOne other = (ToOne) obj;
        return relationInfo == other.relationInfo && getTargetId() == other.getTargetId();
    }

    @Override
    public int hashCode() {
        long targetId = getTargetId();
        return (int) (targetId ^ targetId >>> 32);
    }
}
