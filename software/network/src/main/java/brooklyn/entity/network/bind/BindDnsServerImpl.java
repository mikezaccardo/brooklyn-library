package brooklyn.entity.network.bind;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.DynamicGroup;
import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.entity.group.AbstractMembershipTrackingPolicy;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.location.Location;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.ssh.CommonCommands;
import brooklyn.util.text.Strings;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

/**
 * This sets up a BIND DNS server.
 */
public class BindDnsServerImpl extends SoftwareProcessImpl implements BindDnsServer {

    protected static final Logger LOG = LoggerFactory.getLogger(BindDnsServerImpl.class);

    private Object[] mutex = new Object[0];
    private DynamicGroup entities;
    private AbstractMembershipTrackingPolicy policy;
    private Multimap<Location, Entity> entityLocations = HashMultimap.create();
    protected List<InetAddress> addresses = Lists.newArrayList();

    public BindDnsServerImpl() {
        super();
    }

    public String getManagementCidr() {
        return getConfig(MANAGEMENT_CIDR);
    }

    public Integer getDnsPort() {
        return getAttribute(DNS_PORT);
    }

    public String getDomainName() {
        return getConfig(DOMAIN_NAME);
    }

    @Override
    public void init() {
        entities = addChild(EntitySpecs.spec(DynamicGroup.class)
                .configure("entityFilter", getConfig(ENTITY_FILTER)));
    }

    @Override
    public Class<BindDnsServerDriver> getDriverInterface() {
        return BindDnsServerDriver.class;
    }

    public List<Location> getEntityLocations() {
        synchronized (mutex) {
            return ImmutableList.copyOf(entityLocations.keySet());
        }
    }

    @Override
    public BindDnsServerDriver getDriver() {
        return (BindDnsServerDriver) super.getDriver();
    }

    @Override
    public void connectSensors() {
        connectServiceUpIsRunning();
    }

    @Override
    public void disconnectSensors() {
        disconnectServiceUpIsRunning();
    }

    @Override
    public void onManagementStarted() {
        Map<?, ?> flags = MutableMap.builder()
                .put("name", "Address tracker")
                .put("sensorsToTrack", ImmutableSet.of(getConfig(HOSTNAME_SENSOR)))
                .build();
        policy = new AbstractMembershipTrackingPolicy(flags) {
            @Override
            protected void onEntityChange(Entity member) { added(member); }
            @Override
            protected void onEntityAdded(Entity member) { } // Ignore
            @Override
            protected void onEntityRemoved(Entity member) { removed(member); }
        };
        addPolicy(policy);
        policy.setGroup(entities);

        for (Entity each : entities.getMembers()) {
            added(each);
        }
    }

    public void added(Entity member) {
        synchronized (mutex) {
            Optional<Location> location = Iterables.tryFind(member.getLocations(), Predicates.instanceOf(SshMachineLocation.class));
            String hostname = member.getAttribute(getConfig(HOSTNAME_SENSOR));
            if (location.isPresent() && Strings.isNonBlank(hostname)) {
                SshMachineLocation machine = (SshMachineLocation) location.get();
                if (!entityLocations.containsKey(machine)) {
                    entityLocations.put(machine, member);
                    update(machine);
                }
                LOG.info("{} added at location {} with name {}", new Object[] { member, machine, hostname });
            } else {
                LOG.warn("added({}) called but entity not ready", member);
            }
        }
    }

    public void removed(Entity member) {
        synchronized (mutex) {
            Optional<Location> location = Iterables.tryFind(member.getLocations(), Predicates.instanceOf(SshMachineLocation.class));
            entityLocations.remove(location, member);
            update((SshMachineLocation) location.get());
        }
    }

    public void update(SshMachineLocation machine) {
        synchronized (mutex) {
            String[] templateList = new String[] { "domain.zone", "named.conf" };
            for (String fileName : templateList) {
                String contents = ((BindDnsServerSshDriver) getDriver()).processTemplate("classpath://brooklyn/entity/network/bind/" + fileName);
                machine.copyTo(new ByteArrayInputStream(contents.getBytes()), "/tmp/" + fileName);
                machine.execScript("update bind config", ImmutableList.of(CommonCommands.sudo("cp /tmp/" + fileName + " /var/named/" + fileName)));
            }

            // Restart BIND
            machine.execCommands("configuring BIND", ImmutableList.of(CommonCommands.sudo("service bind restart")));
        }
    }
}
