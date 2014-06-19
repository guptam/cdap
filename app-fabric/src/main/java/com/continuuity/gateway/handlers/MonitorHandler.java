package com.continuuity.gateway.handlers;

import com.continuuity.api.common.Bytes;
import com.continuuity.api.dataset.DatasetProperties;
import com.continuuity.api.dataset.module.DatasetModule;
import com.continuuity.api.dataset.table.OrderedTable;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.common.guice.ConfigModule;
import com.continuuity.common.twill.ReactorServiceManager;
import com.continuuity.data2.datafabric.dataset.DatasetsUtil;
import com.continuuity.data2.dataset2.DatasetDefinitionRegistryFactory;
import com.continuuity.data2.dataset2.DatasetFramework;
import com.continuuity.data2.dataset2.InMemoryDatasetFramework;
import com.continuuity.data2.transaction.DefaultTransactionExecutor;
import com.continuuity.data2.transaction.TransactionAware;
import com.continuuity.data2.transaction.TransactionExecutor;
import com.continuuity.data2.transaction.TransactionFailureException;
import com.continuuity.data2.transaction.inmemory.MinimalTxSystemClient;
import com.continuuity.gateway.auth.Authenticator;
import com.continuuity.gateway.handlers.util.AbstractAppFabricHttpHandler;
import com.continuuity.http.HttpResponder;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMultimap;
import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.name.Named;
import org.apache.hadoop.conf.Configuration;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * Monitor Handler returns the status of different discoverable services
 */
@Path(Constants.Gateway.GATEWAY_VERSION)
public class MonitorHandler extends AbstractAppFabricHttpHandler {
  private static final Logger LOG = LoggerFactory.getLogger(MonitorHandler.class);
  private final Map<String, ReactorServiceManager> reactorServiceManagementMap;
  private static final String STATUSOK = Constants.Monitor.STATUS_OK;
  private static final String STATUSNOTOK = Constants.Monitor.STATUS_NOTOK;
  private static final String NOTAPPLICABLE = "NA";
  private static final Gson GSON = new Gson();
  private final OrderedTable table;
  private final TransactionExecutor txExecutor;

  @Inject
  public MonitorHandler(Authenticator authenticator, CConfiguration cConf, Configuration hConf,
                        Map<String, ReactorServiceManager> serviceMap,
                        @Named("serviceModule") DatasetModule datasetModule) throws Exception {
    super(authenticator);
    this.reactorServiceManagementMap = serviceMap;
    DatasetDefinitionRegistryFactory registryFactory = createInjector(cConf, hConf).getInstance(
      DatasetDefinitionRegistryFactory.class);
    DatasetFramework dsFramework = new InMemoryDatasetFramework(registryFactory);
    dsFramework.addModule("ordered", datasetModule);
    table = DatasetsUtil.getOrCreateDataset(dsFramework, Constants.Service.SERVICE_INFO_TABLE_NAME,
                                                         "orderedTable", DatasetProperties.EMPTY, null);
    txExecutor = new DefaultTransactionExecutor(new MinimalTxSystemClient(), (TransactionAware) table);
  }

  /**
   * Stops Reactor Service
   */
  @Path("/system/services/{service-name}/stop")
  @POST
  public void stopService(final HttpRequest request, final HttpResponder responder,
                          @PathParam("service-name") String serviceName) throws Exception {
    responder.sendStatus(HttpResponseStatus.NOT_IMPLEMENTED);
  }

  /**
   * Starts Reactor Service
   */
  @Path("/system/services/{service-name}/start")
  @POST
  public void startService(final HttpRequest request, final HttpResponder responder,
                           @PathParam("service-name") String serviceName) {
    responder.sendStatus(HttpResponseStatus.NOT_IMPLEMENTED);
  }

  public static String getRequestedServiceInstance(final String serviceName, final OrderedTable table,
                                                    TransactionExecutor txExecutor) throws TransactionFailureException {
    final List<String> list = new ArrayList<String>();
    txExecutor.execute(new TransactionExecutor.Subroutine() {
      @Override
      public void apply() throws Exception {
        list.add(Bytes.toString(table.get(Bytes.toBytes(serviceName), Bytes.toBytes("instance"))));
      }
    });
    return list.get(0);
  }

  public static void setRequestedServiceInstance(final String service, final String value, final OrderedTable table,
                                                  TransactionExecutor txExecutor) throws TransactionFailureException {
    txExecutor.execute(new TransactionExecutor.Subroutine() {
      @Override
      public void apply() throws Exception {
        table.put(Bytes.toBytes(service), Bytes.toBytes("instance"), Bytes.toBytes(value));
      }
    });
  }

  /**
   * Returns the number of instances of Reactor Services
   */
  @Path("/system/services/{service-name}/instances")
  @GET
  public void getServiceInstance(final HttpRequest request, final HttpResponder responder,
                                 @PathParam("service-name") final String serviceName) throws Exception {
    final Map<String, String> instances = new HashMap<String, String>();
    if (reactorServiceManagementMap.containsKey(serviceName)) {
      String actualInstance = String.valueOf(reactorServiceManagementMap.get(serviceName).getInstances());
      instances.put("provisioned", actualInstance);

      String requestedInstance = getRequestedServiceInstance(serviceName, table, txExecutor);
      if (requestedInstance == null) {
        requestedInstance = actualInstance;
        setRequestedServiceInstance(serviceName, actualInstance, table, txExecutor);
      }

      instances.put("requested", requestedInstance);
      responder.sendString(HttpResponseStatus.OK, GSON.toJson(instances));
    } else {
      responder.sendString(HttpResponseStatus.BAD_REQUEST, "Invalid Service Name");
    }
  }

  /**
   * Sets the number of instances of Reactor Services
   */
  @Path("/system/services/{service-name}/instances")
  @PUT
  public void setServiceInstance(final HttpRequest request, final HttpResponder responder,
                                 @PathParam("service-name") final String serviceName) {
    try {
      ReactorServiceManager serviceManager = reactorServiceManagementMap.get(serviceName);
      final int instance = getInstances(request);

      if (instance < serviceManager.getMinInstances() || instance > serviceManager.getMaxInstances()) {
        String response = String.format("Instance count should be between [%s,%s]", serviceManager.getMinInstances(),
                                        serviceManager.getMaxInstances());
        responder.sendString(HttpResponseStatus.BAD_REQUEST, response);
        return;
      } else if (instance == Integer.valueOf(getRequestedServiceInstance(serviceName, table, txExecutor))) {
        responder.sendStatus(HttpResponseStatus.OK);
      }

      setRequestedServiceInstance(serviceName, String.valueOf(instance), table, txExecutor);
      if (serviceManager.setInstances(instance)) {
        responder.sendStatus(HttpResponseStatus.OK);
      } else {
        responder.sendString(HttpResponseStatus.BAD_REQUEST, "Operation Not Valid for this service");
      }
    } catch (Exception e) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST,
                           "Invalid Service Name Or Operation Not Valid for this service");
    }
  }

  //Return the status of reactor services in JSON format
  @Path("/system/services/status")
  @GET
  public void getBootStatus(final HttpRequest request, final HttpResponder responder) {
    Map<String, String> result = new HashMap<String, String>();
    String json;
    for (String service : reactorServiceManagementMap.keySet()) {
      ReactorServiceManager reactorServiceManager = reactorServiceManagementMap.get(service);
      if (reactorServiceManager.canCheckStatus()) {
        String status = reactorServiceManager.isServiceAvailable() ? STATUSOK : STATUSNOTOK;
        result.put(service, status);
      }
    }

    json = (GSON).toJson(result);
    responder.sendByteArray(HttpResponseStatus.OK, json.getBytes(Charsets.UTF_8),
                            ImmutableMultimap.of(HttpHeaders.Names.CONTENT_TYPE, "application/json"));
  }

  @Path("/system/services/{service-id}/status")
  @GET
  public void monitor(final HttpRequest request, final HttpResponder responder,
                      @PathParam("service-id") final String service) {
    if (reactorServiceManagementMap.containsKey(service)) {
      ReactorServiceManager reactorServiceManager = reactorServiceManagementMap.get(service);
      if (reactorServiceManager.canCheckStatus()) {
        if (reactorServiceManager.isServiceAvailable()) {
          responder.sendString(HttpResponseStatus.OK, STATUSOK);
        } else {
          responder.sendString(HttpResponseStatus.NOT_FOUND, STATUSNOTOK);
        }
      } else {
        responder.sendString(HttpResponseStatus.BAD_REQUEST, "Operation not valid for this service");
      }
    } else {
      responder.sendString(HttpResponseStatus.BAD_REQUEST, "Invalid Service Name");
    }
  }

  @Path("/system/services")
  @GET
  public void getServiceSpec(final HttpRequest request, final HttpResponder responder) {
    List<Map<String, String>> serviceSpec = new ArrayList<Map<String, String>>();
    String json;
    SortedSet<String> services = new TreeSet<String>(reactorServiceManagementMap.keySet());
    List<String> serviceList = new ArrayList<String>(services);
    for (String service : serviceList) {
      Map<String, String> spec = new HashMap<String, String>();
      ReactorServiceManager serviceManager = reactorServiceManagementMap.get(service);
      String logs = serviceManager.isLogAvailable() ? Constants.Monitor.STATUS_OK : Constants.Monitor.STATUS_NOTOK;
      String canCheck = serviceManager.canCheckStatus() ? (
        serviceManager.isServiceAvailable() ? STATUSOK : STATUSNOTOK) : NOTAPPLICABLE;
      String minInstance = String.valueOf(serviceManager.getMinInstances());
      String maxInstance = String.valueOf(serviceManager.getMaxInstances());
      String curInstance = String.valueOf(serviceManager.getInstances());
      spec.put("name", service);
      spec.put("logs", logs);
      spec.put("status", canCheck);
      spec.put("min", minInstance);
      spec.put("max", maxInstance);
      spec.put("cur", curInstance);
      //TODO: Add metric name for Event Rate monitoring
      serviceSpec.add(spec);
    }

    json = (GSON).toJson(serviceSpec);
    responder.sendByteArray(HttpResponseStatus.OK, json.getBytes(Charsets.UTF_8),
                            ImmutableMultimap.of(HttpHeaders.Names.CONTENT_TYPE, "application/json"));
  }

  public static Injector createInjector(CConfiguration cConf, Configuration hConf) {
    return Guice.createInjector(
      new ConfigModule(cConf, hConf),
      new MonitorHandlerModule()
    );
  }
}
