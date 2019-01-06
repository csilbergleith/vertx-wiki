package io.vertx.starter;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

public class HttpServerVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

  private FreeMarkerTemplateEngine templateEngine;

  public static final String CONFIG_HTTP_SERVER_PORT = "HTTP.SERVER.PORT";
  public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

  private static final String EMPTY_PAGE_MARKDOWN =
    "# A new page\n" +
      "\n" +
      "Feel-free to write in Markdown!\n";

  private String wikiDbQueue;

  @Override
  public void start(Future<Void> startFuture) throws Exception {
    wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue");

    System.out.println("Setting routes ...");

    HttpServer server = vertx.createHttpServer();

    Router router = Router.router(vertx);
    router.get("/").handler(this::indexHandler);
    router.get("/wiki/:page").handler(this::pageRenderingHandler);
    router.post().handler(BodyHandler.create());
    router.post("/save").handler(this::pageUpdateHandler);
    router.post("/create").handler(this::pageCreateHandler);
    router.post("/delete").handler(this::pageDeleteHandler);

    templateEngine = FreeMarkerTemplateEngine.create(vertx);

    System.out.println("Starting Server .... ");

    int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080);
    server
      .requestHandler(router)
      .listen(portNumber, ar -> {
        if(ar.succeeded()) {
          LOGGER.info("HTTP server running on port " + portNumber);
          startFuture.complete();
        } else {
          LOGGER.error("Could not start a HTTP server", ar.cause());
          startFuture.fail(ar.cause());
        }
      });
  }

  private void indexHandler(RoutingContext context) {

    DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages");

    vertx.eventBus().send(wikiDbQueue, new JsonObject(), options, reply ->{
      if (reply.succeeded()) {
        JsonObject body = (JsonObject) reply.result().body();
        context.put("title", "Wiki home");
        context.put("pages", body.getJsonArray("pages").getList());
        templateEngine.render(context.data(), "templates/index.ftl", ar -> {
          if (ar.succeeded()) {
            context.response().putHeader("Content-Type", "text/html");
            context.response().end(ar.result());
          } else {
            context.fail(ar.cause());
          }
        });
      } else {context.fail(reply.cause());}
    });

//    dbClient.getConnection(car -> {
//      if (car.succeeded()) {
//        SQLConnection connection = car.result();
//        connection.query(SQL_ALL_PAGES, res -> {
//          connection.close();
//          if (res.succeeded()) {
//            List<String> pages = res.result()
//              .getResults()
//              .stream()
//              .map(json -> json.getString(0))
//              .sorted()
//              .collect(Collectors.toList());
//
//            context.put("title", "Wiki home");
//            context.put("pages", pages);
//            templateEngine.render(context.data(), "templates/index.ftl", ar -> {
//              if (ar.succeeded()) {
//                context.response().putHeader("Content-Type", "text/html");
//                context.response().end(ar.result());
//              } else {
//                context.fail(ar.cause());
//              }
//            });
//          } else {
//            context.fail(res.cause());
//          }
//        });
//      } else {
//        context.fail(car.cause());
//      }
//    });
  }

  private void pageRenderingHandler(RoutingContext context){
    String requestedPage = context.request().getParam("page");
    JsonObject request = new JsonObject().put("page", requestedPage);

    DeliveryOptions options = new DeliveryOptions().addHeader("action", "get-page");
    vertx.eventBus().send(wikiDbQueue, request, options, reply -> {
      if (reply.succeeded()) {
        JsonObject body = (JsonObject) reply.result().body();

        boolean found = body.getBoolean("found");
        String rawContent = body.getString("rawContent", EMPTY_PAGE_MARKDOWN);
        context.put("title", requestedPage);
        context.put("id", body.getInteger("id"));
        context.put("newPage", found ? "yes" : "no");
        context.put("rawContent", rawContent);
        context.put("content", Processor.process(rawContent));
        context.put("timestamp", new Date().toString());

        templateEngine.render(context.data(), "templates/requestedPage.ftl", ar -> {
          if (ar.succeeded()) {
            context.response().putHeader("Content-Type", "text/html");
            context.response().end(ar.result());
          } else { context.fail(ar.cause());}
        });
      } else {context.fail(reply.cause());}
    });
  }

  private void pageCreateHandler(RoutingContext context) {
    String pageName = context.request().getParam("name");
    String location = "/wiki/" + pageName;
    if (pageName == null || pageName.isEmpty()) {
      location = "/";
    }
    context.response().setStatusCode(303);
    context.response().putHeader("Location", location);
    context.response().end("Redirecting");
  }

  private void pageUpdateHandler(RoutingContext context) {
    String title = context.request().getParam("title");
    JsonObject request = new JsonObject()
      .put("id", context.request().getParam("id"))
      .put("title", title)
      .put("markdown", context.request().getParam("markdown"));

    DeliveryOptions options = new DeliveryOptions();
    if ("yes".equalsIgnoreCase((context.request().getParam("newPage")))) {
      options.addHeader("action", "create-page");
    } else {
      options.addHeader("action", "save-page");
    }

    vertx.eventBus().send(wikiDbQueue, request, options, reply -> {
      if(reply.succeeded()) {
        context.response().setStatusCode(303);
        context.response().putHeader("Location", "/wiki/" + title);
        context.response().end("Redirecting");
      } else {context.fail(reply.cause());}
      });
  }

  private void pageDeleteHandler (RoutingContext context) {
    String id = context.request().getParam("id");

    JsonObject request = new JsonObject().put("id", id);
    DeliveryOptions options = new DeliveryOptions().addHeader("action", "delete-page");
    vertx.eventBus().send(wikiDbQueue, request, options, reply -> {
      if (reply.succeeded()) {
        context.response().setStatusCode(303);
        context.response().putHeader("Location", "/");
        context.response().end("Redirecting");
      } else {context.fail(reply.cause());}
    });
  }
}
