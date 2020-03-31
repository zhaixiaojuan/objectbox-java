package io.objectbox.sync;

import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ConnectivityMonitorTest {

    @Test
    public void reactsToObserverChanges() {
        TestSyncClient testSyncClient = new TestSyncClient();
        TestConnectivityMonitor testMonitor = new TestConnectivityMonitor();

        // No observer set.
        testMonitor.removeObserver();
        assertEquals(0, testMonitor.onObserverSetCalled);
        assertEquals(1, testMonitor.onObserverRemovedCalled);

        testMonitor.reset();

        testMonitor.setObserver(testSyncClient);
        assertEquals(1, testMonitor.onObserverSetCalled);
        assertEquals(0, testMonitor.onObserverRemovedCalled);

        testMonitor.reset();

        testMonitor.removeObserver();
        assertEquals(0, testMonitor.onObserverSetCalled);
        assertEquals(1, testMonitor.onObserverRemovedCalled);
    }

    @Test
    public void settingNullObserverFails() {
        TestConnectivityMonitor testMonitor = new TestConnectivityMonitor();

        //noinspection ConstantConditions Ignore NotNull annotation on purpose.
        assertThrows(IllegalArgumentException.class, () -> testMonitor.setObserver(null));
    }

    @Test
    public void notifiesObserversOnlyIfSet() {
        TestSyncClient testSyncClient = new TestSyncClient();
        TestConnectivityMonitor testMonitor = new TestConnectivityMonitor();

        testMonitor.setObserver(testSyncClient);
        testMonitor.notifyConnectionAvailable();
        assertEquals(1, testSyncClient.notifyConnectionAvailableCalled);

        testSyncClient.reset();

        testMonitor.removeObserver();
        testMonitor.notifyConnectionAvailable();
        assertEquals(0, testSyncClient.notifyConnectionAvailableCalled);
    }

    private static class TestConnectivityMonitor extends ConnectivityMonitor {

        int onObserverSetCalled;
        int onObserverRemovedCalled;

        void reset() {
            onObserverSetCalled = 0;
            onObserverRemovedCalled = 0;
        }

        @Override
        public void onObserverSet() {
            onObserverSetCalled += 1;
        }

        @Override
        public void onObserverRemoved() {
            onObserverRemovedCalled += 1;
        }
    }

    private static class TestSyncClient implements SyncClient {

        int notifyConnectionAvailableCalled;

        void reset() {
            notifyConnectionAvailableCalled = 0;
        }

        @Override
        public String getServerUrl() {
            return null;
        }

        @Override
        public boolean isStarted() {
            return false;
        }

        @Override
        public boolean isLoggedIn() {
            return false;
        }

        @Override
        public long getLastLoginCode() {
            return 0;
        }

        @Override
        public void setSyncListener(SyncClientListener listener) {

        }

        @Override
        public void removeSyncListener() {

        }

        @Override
        public void setSyncChangesListener(SyncChangesListener listener) {

        }

        @Override
        public void removeSyncChangesListener() {

        }

        @Override
        public void setLoginCredentials(SyncCredentials credentials) {

        }

        @Override
        public boolean awaitFirstLogin(long millisToWait) {
            return false;
        }

        @Override
        public void start() {

        }

        @Override
        public void stop() {

        }

        @Override
        public void close() {

        }

        @Override
        public void requestUpdates() {

        }

        @Override
        public void requestUpdatesOnce() {

        }

        @Override
        public void cancelUpdates() {

        }

        @Override
        public void requestFullSync() {

        }

        @Override
        public void notifyConnectionAvailable() {
            notifyConnectionAvailableCalled += 1;
        }
    }

}
