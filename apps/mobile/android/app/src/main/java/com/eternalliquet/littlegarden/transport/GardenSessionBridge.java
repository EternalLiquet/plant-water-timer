package com.eternalliquet.littlegarden.transport;

/** Boolean-only session operations. A null transport keeps an unaccepted build offline. */
public final class GardenSessionBridge implements AutoCloseable {
    public static final class Unavailable extends Exception {
        Unavailable() { super("NATIVE_VALIDATION_REQUIRED"); }
    }
    private final GardenSessionTransport transport;

    public GardenSessionBridge(GardenSessionTransport transport) { this.transport = transport; }
    public boolean available() { return transport != null; }

    private GardenSessionTransport requireTransport() throws Unavailable {
        if (transport == null) throw new Unavailable();
        return transport;
    }

    public GardenSessionTransport.Session session() throws Unavailable, GardenSessionTransport.Failure {
        return requireTransport().session();
    }

    public GardenSessionTransport.Session login(String password) throws Unavailable, GardenSessionTransport.Failure {
        GardenSessionTransport client = requireTransport();
        client.login(password);
        return client.session();
    }

    public GardenSessionTransport.Session logout() throws Unavailable, GardenSessionTransport.Failure {
        requireTransport().logout();
        return new GardenSessionTransport.Session(false, true);
    }

    @Override public void close() { if (transport != null) transport.close(); }
}
