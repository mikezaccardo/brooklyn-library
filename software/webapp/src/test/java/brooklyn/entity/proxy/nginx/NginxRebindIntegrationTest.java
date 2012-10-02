package brooklyn.entity.proxy.nginx;

import static brooklyn.test.EntityTestUtils.assertAttributeEqualsEventually;
import static brooklyn.test.HttpTestUtils.assertHttpStatusCodeEquals;
import static brooklyn.test.HttpTestUtils.assertHttpStatusCodeEventuallyEquals;
import static org.testng.Assert.assertEquals;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.BasicGroup;
import brooklyn.entity.basic.SoftwareProcessEntity;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.rebind.RebindTestUtils;
import brooklyn.entity.webapp.jboss.JBoss7Server;
import brooklyn.entity.webapp.jboss.JBoss7ServerFactory;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.LocalManagementContext;
import brooklyn.mementos.BrooklynMemento;
import brooklyn.test.WebAppMonitor;
import brooklyn.test.entity.TestApplication;
import brooklyn.util.MutableMap;
import brooklyn.util.internal.TimeExtras;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * Test the operation of the {@link NginxController} class.
 */
public class NginxRebindIntegrationTest {
    private static final Logger LOG = LoggerFactory.getLogger(NginxRebindIntegrationTest.class);

    static { TimeExtras.init(); }

    private URL warUrl;
    private LocalhostMachineProvisioningLocation localhostProvisioningLocation;
    private TestApplication origApp;
    private TestApplication newApp;
    private List<WebAppMonitor> webAppMonitors = new CopyOnWriteArrayList<WebAppMonitor>();
	private ExecutorService executor;
    
    @BeforeMethod(groups = "Integration")
    public void setUp() {
        warUrl = getClass().getClassLoader().getResource("hello-world.war");

    	localhostProvisioningLocation = new LocalhostMachineProvisioningLocation();
        origApp = new TestApplication();
        executor = Executors.newCachedThreadPool();
    }

    @AfterMethod(groups = "Integration", alwaysRun=true)
    public void tearDown() throws Exception {
        for (WebAppMonitor monitor : webAppMonitors) {
        	monitor.terminate();
        }
        if (executor != null) executor.shutdownNow();
        if (newApp != null) newApp.stop();
        if (origApp != null) origApp.stop();
    }

    private WebAppMonitor newWebAppMonitor(String url, int expectedResponseCode) {
    	WebAppMonitor monitor = new WebAppMonitor(url)
    	        .delayMillis(0)
    			.expectedResponseCode(expectedResponseCode)
		    	.logFailures(LOG);
    	webAppMonitors.add(monitor);
    	executor.execute(monitor);
    	return monitor;
    }
    
    /**
     * Test can rebind to the simplest possible nginx configuration (i.e. no server pool).
     */
    @Test(groups = "Integration")
    public void testRebindsWithEmptyServerPool() throws Exception {
    	
        // Set up nginx with a server pool
        DynamicCluster origServerPool = new DynamicCluster(MutableMap.of("factory", new JBoss7ServerFactory(), "initialSize", 0), origApp);
        
        NginxController origNginx = new NginxController(MutableMap.builder()
                .put("owner", origApp)
                .put("serverPool", origServerPool)
                .put("domain", "localhost")
                .build());
        new LocalManagementContext().manage(origApp);
        
        // Start the app, and ensure reachable; start polling the URL
        origApp.start(ImmutableList.of(localhostProvisioningLocation));
        
        String rootUrl = origNginx.getAttribute(NginxController.ROOT_URL);

        assertHttpStatusCodeEventuallyEquals(rootUrl, 404);
        WebAppMonitor monitor = newWebAppMonitor(rootUrl, 404);
        final String origConfigFile = origNginx.getConfigFile();
        
        newApp = (TestApplication) serializeRebindManageAndDisconnectOldNginx(origApp, getClass().getClassLoader());
        final NginxController newNginx = (NginxController) Iterables.find(newApp.getOwnedChildren(), Predicates.instanceOf(NginxController.class));

        assertEquals(newNginx.getConfigFile(), origConfigFile);
        
        assertEquals(newNginx.getAttribute(NginxController.ROOT_URL), rootUrl);
        assertEquals(newNginx.getAttribute(NginxController.PROXY_HTTP_PORT), origNginx.getAttribute(NginxController.PROXY_HTTP_PORT));
        assertEquals(newNginx.getConfig(NginxController.STICKY), origNginx.getConfig(NginxController.STICKY));
        
        assertAttributeEqualsEventually(newNginx, SoftwareProcessEntity.SERVICE_UP, true);
        assertHttpStatusCodeEventuallyEquals(rootUrl, 404);
        
        assertEquals(monitor.getFailures(), 0);
    }
    
    /**
     * Test can rebind to the simplest possible nginx configuration (i.e. no server pool).
     */
    @Test(groups = "Integration")
    public void testRebindsWithoutLosingServerPool() throws Exception {
        
        // Set up nginx with a server pool
        DynamicCluster origServerPool = new DynamicCluster(
                MutableMap.of("factory", new JBoss7ServerFactory(MutableMap.of("war", warUrl.toString())), "initialSize", 1), 
                origApp);
        
        NginxController origNginx = new NginxController(MutableMap.builder()
                .put("owner", origApp)
                .put("domain", "localhost")
                .put("serverPool", origServerPool)
                .build());
        new LocalManagementContext().manage(origApp);

        // Start the app, and ensure reachable; start polling the URL
        origApp.start(ImmutableList.of(localhostProvisioningLocation));
        
        String rootUrl = origNginx.getAttribute(NginxController.ROOT_URL);
        JBoss7Server origJboss = (JBoss7Server) Iterables.getOnlyElement(origServerPool.getMembers());

        assertHttpStatusCodeEventuallyEquals(rootUrl, 200);
        WebAppMonitor monitor = newWebAppMonitor(rootUrl, 200);
        final String origConfigFile = origNginx.getConfigFile();
        
        // Rebind
        newApp = (TestApplication) serializeRebindManageAndDisconnectOldNginx(origApp, getClass().getClassLoader());
        ManagementContext newManagementContext = newApp.getManagementContext();
        final NginxController newNginx = (NginxController) Iterables.find(newApp.getOwnedChildren(), Predicates.instanceOf(NginxController.class));
        DynamicCluster newServerPool = (DynamicCluster) newManagementContext.getEntity(origServerPool.getId());
        
        assertAttributeEqualsEventually(newNginx, SoftwareProcessEntity.SERVICE_UP, true);
        assertHttpStatusCodeEventuallyEquals(rootUrl, 200);

        assertEquals(newNginx.getConfigFile(), origConfigFile);
        
        // Check that an update doesn't break things
        newNginx.update();

        assertHttpStatusCodeEquals(rootUrl, 200);

        // Resize new cluster, and confirm change takes affect.
        //  - Increase size
        //  - wait for nginx to definitely be updates (TODO nicer way to wait for updated?)
        //  - terminate old servers (through origNginx, so looks like a failure!)
        //  - confirm can still route messages
        newServerPool.resize(2);
        
        Thread.sleep(10*1000);
        
        origJboss.stop();

        assertHttpStatusCodeEventuallyEquals(rootUrl, 200);

        // Check that URLs have been constantly reachable
        assertEquals(monitor.getFailures(), 0);
    }
    
    
    /**
     * Test can rebind to the simplest possible nginx configuration (i.e. no server pool).
     */
    @Test(groups = "Integration")
    public void testRebindsWithoutLosingUrlMappings() throws Exception {
        
        // Set up nginx with a url-mapping
        Group origUrlMappingsGroup = new BasicGroup(MutableMap.of("childrenAsMembers", true), origApp);

        DynamicCluster origMappingPool = new DynamicCluster(
                MutableMap.of("factory", new JBoss7ServerFactory(MutableMap.of("war", warUrl.toString())), "initialSize", 1), 
                origApp);
        UrlMapping origMapping = new UrlMapping(
                MutableMap.builder()
                        .put("domain", "localhost1")
                        .put("target", origMappingPool)
                        .put("rewrites", ImmutableList.of(new UrlRewriteRule("/foo/(.*)", "/$1")))
                        .build(),
                origUrlMappingsGroup);

        NginxController origNginx = new NginxController(MutableMap.builder()
                .put("owner", origApp)
                .put("domain", "localhost")
                .put("urlMappings", origUrlMappingsGroup)
                .build());
        new LocalManagementContext().manage(origApp);

        // Start the app, and ensure reachable; start polling the URL
        origApp.start(ImmutableList.of(localhostProvisioningLocation));
        
        String mappingGroupUrl = "http://localhost1:"+origNginx.getAttribute(NginxController.PROXY_HTTP_PORT)+"/foo/";
        JBoss7Server origJboss = (JBoss7Server) Iterables.getOnlyElement(origMappingPool.getMembers());

        assertHttpStatusCodeEventuallyEquals(mappingGroupUrl, 200);
        WebAppMonitor monitor = newWebAppMonitor(mappingGroupUrl, 200);
        final String origConfigFile = origNginx.getConfigFile();
        
        // Create a rebinding
        newApp = (TestApplication) serializeRebindManageAndDisconnectOldNginx(origApp, getClass().getClassLoader());
        ManagementContext newManagementContext = newApp.getManagementContext();
        final NginxController newNginx = (NginxController) Iterables.find(newApp.getOwnedChildren(), Predicates.instanceOf(NginxController.class));
        DynamicCluster newMappingPool = (DynamicCluster) newManagementContext.getEntity(origMappingPool.getId());
        
        assertAttributeEqualsEventually(newNginx, SoftwareProcessEntity.SERVICE_UP, true);
        assertHttpStatusCodeEventuallyEquals(mappingGroupUrl, 200);
        
        assertEquals(newNginx.getConfigFile(), origConfigFile);
        
        // Check that an update doesn't break things
        newNginx.update();

        assertHttpStatusCodeEquals(mappingGroupUrl, 200);

        // Resize new cluster, and confirm change takes affect.
        //  - Increase size
        //  - wait for nginx to definitely be updates (TODO nicer way to wait for updated?)
        //  - terminate old servers (through origApp so looks like failure)
        //  - confirm can still route messages
        newMappingPool.resize(2);
        
        Thread.sleep(10*1000);
        
        origJboss.stop();

        assertHttpStatusCodeEquals(mappingGroupUrl, 200);

        // Check that URLs have been constantly reachable
        assertEquals(monitor.getFailures(), 0);
    }
    
    private Application serializeRebindManageAndDisconnectOldNginx(Application origApp, ClassLoader classLoader) throws Exception {
        BrooklynMemento brooklynMemento = RebindTestUtils.serialize(origApp);
        
        // after serializing, but before rebinding, unmanage the origNginx so it won't interfere
        ManagementContext origManagementContext = origApp.getManagementContext();
        Iterable<Entity> origNginxes = Iterables.filter(origManagementContext.getEntities(), Predicates.instanceOf(NginxController.class));
        for (Entity origNginx : origNginxes) {
            origManagementContext.unmanage(origNginx);
        }
        
        return RebindTestUtils.rebind(brooklynMemento, classLoader);
    }
}
