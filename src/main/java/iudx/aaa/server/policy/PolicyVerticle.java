package iudx.aaa.server.policy;

import static iudx.aaa.server.policy.Constants.APD_SERVICE_ADDRESS;
import static iudx.aaa.server.policy.Constants.DB_RECONNECT_ATTEMPTS;
import static iudx.aaa.server.policy.Constants.DB_RECONNECT_INTERVAL_MS;
import static iudx.aaa.server.policy.Constants.POLICY_SERVICE_ADDRESS;
import static iudx.aaa.server.policy.Constants.REGISTRATION_SERVICE_ADDRESS;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.sqlclient.PoolOptions;
import iudx.aaa.server.apd.ApdService;
import iudx.aaa.server.registration.RegistrationService;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The Policy Verticle.
 *
 * <h1>Policy Verticle</h1>
 *
 * <p>The Policy Verticle implementation in the IUDX AAA Server exposes the {@link
 * iudx.aaa.server.policy.PolicyService} over the Vert.x Event Bus.
 *
 * @version 1.0
 * @since 2020-12-15
 */
public class PolicyVerticle extends AbstractVerticle {

  /* Database Properties */
  private String databaseIP;
  private int databasePort;
  private String databaseName;
  private String databaseSchema;
  private String databaseUserName;
  private String databasePassword;
  private int poolSize;
  private PoolOptions poolOptions;
  private PgConnectOptions connectOptions;
  private JsonObject catalogueOptions;
  private PolicyService policyService;
  private RegistrationService registrationService;
  private ApdService apdService;
  private CatalogueClient catalogueClient;
  private ServiceBinder binder;
  private MessageConsumer<JsonObject> consumer;
  private static final Logger LOGGER = LogManager.getLogger(PolicyVerticle.class);

  /**
   * This method is used to start the Verticle. It deploys a verticle in a cluster, registers the
   * service with the Event bus against an address, publishes the service with the service discovery
   * interface.
   */
  @Override
  public void start() throws Exception {

    /* Read the configuration and set the postgres client properties. */
    LOGGER.debug("Info : {} : Reading config file", LOGGER.getName());

    databaseIP = config().getString("databaseIP");
    databasePort = Integer.parseInt(config().getString("databasePort"));
    databaseName = config().getString("databaseName");
    databaseSchema = config().getString("databaseSchema");
    databaseUserName = config().getString("databaseUserName");
    databasePassword = config().getString("databasePassword");
    poolSize = Integer.parseInt(config().getString("poolSize"));
    catalogueOptions = config().getJsonObject("catalogueOptions");

    /* Set Connection Object and schema */
    if (connectOptions == null) {
      Map<String, String> schemaProp = Map.of("search_path", databaseSchema);

      connectOptions =
          new PgConnectOptions()
              .setPort(databasePort)
              .setHost(databaseIP)
              .setDatabase(databaseName)
              .setUser(databaseUserName)
              .setPassword(databasePassword)
              .setProperties(schemaProp)
              .setReconnectAttempts(DB_RECONNECT_ATTEMPTS)
              .setReconnectInterval(DB_RECONNECT_INTERVAL_MS);
    }

    /* Pool options */
    if (poolOptions == null) {
      poolOptions = new PoolOptions().setMaxSize(poolSize);
    }

    WebClientOptions clientOptions =
        new WebClientOptions().setSsl(true).setVerifyHost(true).setTrustAll(false);

    WebClient webClientForCatClient = WebClient.create(vertx, clientOptions);

    /* Create the client pool */
    PgPool pool = PgPool.pool(vertx, connectOptions, poolOptions);
    registrationService = RegistrationService.createProxy(vertx, REGISTRATION_SERVICE_ADDRESS);
    apdService = ApdService.createProxy(vertx, APD_SERVICE_ADDRESS);
    catalogueClient = new CatalogueClient(webClientForCatClient, catalogueOptions);
    policyService = new PolicyServiceImpl(pool, registrationService, apdService, catalogueClient);

    binder = new ServiceBinder(vertx);
    consumer =
        binder.setAddress(POLICY_SERVICE_ADDRESS).register(PolicyService.class, policyService);

    LOGGER.debug("Info : {} : Started", LOGGER.getName());
  }

  @Override
  public void stop() {
    binder.unregister(consumer);
  }
}
