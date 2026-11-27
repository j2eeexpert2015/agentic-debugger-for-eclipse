package org.learningfromexperience.eclipse.agentdebug;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import org.learningfromexperience.eclipse.agentdebug.bridge.EclipseBridgeServer;

/**
 * Manages the Eclipse plugin lifecycle and debugging bridge.
 *
 * @author Ayan Dutta
 */
public class Activator implements BundleActivator {

    private static BundleContext context;

    private static EclipseBridgeServer bridgeServer;

    /** OPEN-61: true while the bridge server is running (for the setup check). */
    public static synchronized boolean bridgeRunning() {
        return bridgeServer != null;
    }

    static BundleContext getContext() {
        return context;
    }

    public static synchronized void setBridgeServer(
            EclipseBridgeServer server) {

        if (bridgeServer != null) {
            bridgeServer.close();
        }

        bridgeServer = server;
    }

    @Override
    public void start(BundleContext bundleContext) throws Exception {

        Activator.context = bundleContext;

        System.out.println(
                "[Eclipse Agentic Debugger] Plugin started successfully."
        );
    }

    @Override
    public void stop(BundleContext bundleContext) throws Exception {

        System.out.println(
                "[Eclipse Agentic Debugger] Plugin stopping."
        );

        synchronized (Activator.class) {

            if (bridgeServer != null) {
                bridgeServer.close();
                bridgeServer = null;
            }
        }

        Activator.context = null;

        System.out.println(
                "[Eclipse Agentic Debugger] Plugin stopped."
        );
    }
}