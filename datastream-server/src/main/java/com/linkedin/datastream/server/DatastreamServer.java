package com.linkedin.datastream.server;

import com.linkedin.datastream.common.DatastreamException;
import com.linkedin.datastream.common.VerifiableProperties;

import com.linkedin.datastream.server.assignment.BroadcastStrategy;
import com.linkedin.datastream.server.dms.DatastreamStore;
import com.linkedin.datastream.server.dms.ZookeeperBackedDatastreamStore;
import com.linkedin.datastream.server.zk.ZkClient;
import com.linkedin.restli.server.NettyStandaloneLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;


/**
 * DatastreamServer is the entry point for starting datastream services. It is a container
 * for all datastream services including the rest api service, the coordinator and so on.
 * DatastreamServer is designed to be singleton.
 */
public enum DatastreamServer {
  INSTANCE;

  private static final String CONFIG_PREFIX = "datastream.server.";
  private static final Logger LOG = LoggerFactory.getLogger(DatastreamServer.class.getName());

  private Coordinator _coordinator;
  private DatastreamStore _datastreamStore;
  private boolean _isInitialized = false;

  public synchronized boolean isInitialized() {
    return _isInitialized;
  }

  public Coordinator getCoordinator() {
    return _coordinator;
  }

  public DatastreamStore getDatastreamStore() {
    return _datastreamStore;
  }

  public synchronized void init(Properties properties) throws DatastreamException {
    if (isInitialized()) {
      return;
    }
    LOG.info("Creating coordinator.");
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    CoordinatorConfig coordinatorConfig = new CoordinatorConfig(verifiableProperties);
    _coordinator = new Coordinator(coordinatorConfig);

    LOG.info("Loading connectors.");
    String connectorStrings = verifiableProperties.getString(CONFIG_PREFIX + "connectorTypes");
    ClassLoader classLoader = DatastreamServer.class.getClassLoader();
    for (String connector : connectorStrings.split(",")) {
      try {
        // For each connector type defined in the config, load one instance from that class
        Class connectorClass = classLoader.loadClass(connector);
        // TODO: set up connector config here when we have any
        Connector connectorInstance = (Connector) connectorClass.newInstance();

        // Read the assignment startegy from the config; if not found, use default strategy
        AssignmentStrategy assignmentStrategy;
        String strategy = verifiableProperties.getString(connector + ".assignmentStrategy", "");
        if (!strategy.isEmpty()) {
          Class assignmentStrategyClass = classLoader.loadClass(strategy);
          assignmentStrategy = (AssignmentStrategy) assignmentStrategyClass.newInstance();
        } else {
          // TODO: default strategy should be SimpleStrategy, which doesn't exist for now
          assignmentStrategy = new BroadcastStrategy();
        }
        _coordinator.addConnector(connectorInstance, assignmentStrategy);
      } catch (Exception ex) {
        throw new DatastreamException("Failed to instantiate connector: " + connector, ex);
      }
    }

    LOG.info("Setting up DMS endpoint server.");
    ZkClient zkClient = new ZkClient(coordinatorConfig.getZkAddress(),
                                     coordinatorConfig.getZkSessionTimeout(),
                                     coordinatorConfig.getZkConnectionTimeout());
    _datastreamStore = new ZookeeperBackedDatastreamStore(zkClient, coordinatorConfig.getCluster());
    int httpPort = verifiableProperties.getIntInRange(CONFIG_PREFIX + "httpport", 1, 65535);
    NettyStandaloneLauncher launcher = new NettyStandaloneLauncher(httpPort, "com.linkedin.datastream.server.dms");

    try {
      launcher.start();
    } catch (IOException ex) {
      throw new DatastreamException("Failed to start netty.", ex);
    }

    verifiableProperties.verify();
    _isInitialized = true;

    LOG.info("DatastreamServer initialized.");
  }
}
