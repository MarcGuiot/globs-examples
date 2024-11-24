package org.globsframework.sample.generic;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.http.impl.nio.bootstrap.HttpServer;
import org.apache.http.impl.nio.bootstrap.ServerBootstrap;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.globsframework.commandline.ParseCommandLine;
import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobModelBuilder;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeLoaderFactory;
import org.globsframework.core.metamodel.annotations.*;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.utils.Files;
import org.globsframework.core.utils.Strings;
import org.globsframework.core.utils.collections.Pair;
import org.globsframework.graphql.GQLGlobCaller;
import org.globsframework.graphql.GQLGlobCallerBuilder;
import org.globsframework.graphql.GlobSchemaGenerator;
import org.globsframework.graphql.OnLoad;
import org.globsframework.graphql.db.ConnectionBuilder;
import org.globsframework.graphql.model.AllGraphQLAnnotations;
import org.globsframework.graphql.model.GQLQueryParam;
import org.globsframework.graphql.model.GraphQlResponse;
import org.globsframework.graphql.parser.GqlField;
import org.globsframework.http.GlobHttpContent;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.http.HttpTreatmentWithHeader;
import org.globsframework.json.GSonUtils;
import org.globsframework.json.annottations.AllJsonAnnotations;
import org.globsframework.json.annottations.IsJsonContent_;
import org.globsframework.sql.*;
import org.globsframework.sql.annotations.AllSqlAnnotations;
import org.globsframework.sql.constraints.Constraint;
import org.globsframework.sql.constraints.Constraints;
import org.globsframework.sql.drivers.jdbc.DataSourceSqlService;
import org.globsframework.sql.drivers.jdbc.DbType;
import org.globsframework.sql.drivers.jdbc.MappingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

/*

start with following argument --model path_to_model (simplest/src/main/resources/model.json for example)

--dbUrl jdbc:hsqldb:file:./db/ --user sa --password ""
or
--dbUrl jdbc:postgresql://localhost/postgres --user postgres --password xxxx

The code create the Db if needed.

if you init the db with Example2 and use the model resources/model.json then :

you can query db with graphQl:
curl 'http://localhost:4000/api/graphql' --data-binary '{"query":"{\n  professors: professors{\n   uuid\n    firstName\n    lastName\n    mainClasses{\n      name\n      students{\n        totalCount\n      }\n    }\n  }\n  allClasses: classes{\n     name\n     students{\n      totalCount\n       edges{\n         node{\n           firstName\n           lastName\n         }\n       }\n     }\n   }\n}","variables":{}}'

The code expose on default port 4000 a
REST api route /api/{class, student, professor} en post/put/get
OPEN API under /api/openapi
GRAPHQL route under /graphql
 */

public class GenericApiExpose {

    public static final Logger LOGGER = LoggerFactory.getLogger(GenericApiExpose.class);

    public static void main(String[] args) throws InterruptedException, IOException {

        // parse argument with default value.
        Glob argument = ParseCommandLine.parse(ArgumentType.TYPE, args);

        // retrieve jdbc url, user, etc to init Hikari pool.
        DbType typeOfDb = DbType.fromString(argument.getNotEmpty(ArgumentType.dbUrl));
        HikariConfig configuration = new HikariConfig();
        configuration.setUsername(argument.getNotEmpty(ArgumentType.user));
        configuration.setPassword(argument.get(ArgumentType.password));
        configuration.setJdbcUrl(argument.getNotEmpty(ArgumentType.dbUrl));

        // create a SqlService based on datasource.
        // SqlService is the entry point to access the db.
        SqlService sqlService = new DataSourceSqlService(
                MappingHelper.get(typeOfDb), new HikariDataSource(configuration), typeOfDb);


        String strModel = Files.read(new FileInputStream(argument.getNotEmpty(ArgumentType.model)), StandardCharsets.UTF_8);

        Glob model = GSonUtils.decode(strModel, Model.TYPE);

        GlobModel globModel = GlobModelBuilder.create(Parameter.TYPE, SearchQuery.TYPE, EntityQuery.TYPE, DbTarget.TYPE, Link.TYPE, Searchable.TYPE, IsConnection.TYPE)
                .add(AllCoreAnnotations.MODEL)
                .add(AllJsonAnnotations.MODEL)
                .add(AllSqlAnnotations.MODEL)
                .add(AllGraphQLAnnotations.INSTANCE)
                .get();

        GlobModel resources = GlobModelBuilder.create(Arrays.stream(model.getOrEmpty(Model.dbTypes))
                .map(s -> GSonUtils.decodeGlobType(s, globModel, true)).toArray(GlobType[]::new)).get();

        Optional<GlobType> graphQLSchemaType = model.getOpt(Model.graphqlTypes)
                .map(str -> GSonUtils.decodeGlobType(str, globModel, true));

        {
            SqlConnection db = sqlService.getDb();
            //create tables if they do not exist in db.
            resources.getAll().forEach(db::createTable);
            db.commitAndClose();
        }

        // create an HttpServerRegister to register Http end point.
        final HttpServerRegister httpServerRegister = new HttpServerRegister("EstablishmentServer/0.1");

        // for each resource we register post, put, get.
        for (GlobType resource : resources) {
            httpServerRegister.register("/api/" + resource.getName().toLowerCase(), null)
                    .post(resource, null, (body, pathParameters, queryParameters) -> {

                        // get the key
                        StringField keyField = resource.getFieldWithAnnotation(KeyField.UNIQUE_KEY).asStringField();
                        String uuid = UUID.randomUUID().toString();
                        SqlConnection db = sqlService.getDb();
                        try {
                            //an insert into request
                            CreateBuilder createBuilder = db.getCreateBuilder(resource);

                            for (Field field : resource.getFields()) {
                                //ignore key field.
                                if (!field.isKeyField()) {
                                    //if value is not null add it to the insert request.
                                    body.getOptValue(field).ifPresent(v -> createBuilder.setObject(field, v));
                                }
                            }

                            // add uuid
                            createBuilder.set(keyField, uuid);

                            try (SqlRequest insertRequest = createBuilder.getRequest()) {
                                // execute the request.
                                insertRequest.run();
                            }
                        } finally {
                            db.commitAndClose();
                        }

                        return retrieveResource(resource, db, keyField, uuid);
                    })
                    .declareReturnType(resource);

            HttpServerRegister.Verb onUrl = httpServerRegister.register("/api/" + resource.getName().toLowerCase() + "/{uuid}", UrlType.TYPE);
            onUrl.put(resource, null, (body, pathParameters, queryParameters) -> {
                        SqlConnection db = sqlService.getDb();
                        StringField keyField = resource.getFieldWithAnnotation(KeyField.UNIQUE_KEY).asStringField();
                        String uuid = pathParameters.getNotEmpty(UrlType.uuid);
                        UpdateBuilder updateBuilder = db.getUpdateBuilder(resource, Constraints.equal(keyField, uuid));

                        for (Field field : resource.getFields()) {
                            if (!field.isKeyField()) {
                                // if set => can be null!
                                if (body.isSet(field)) {
                                    updateBuilder.updateUntyped(field, body.getValue(field));
                                }
                            }
                        }

                        try (SqlRequest insertRequest = updateBuilder.getRequest()) {
                            insertRequest.run();
                        } finally {
                            db.commit();
                        }

                        return retrieveResource(resource, db, keyField, uuid);
                    })
                    .declareReturnType(resource);

            onUrl.get(null, (body, pathParameters, queryParameters) -> {
                        SqlConnection db = sqlService.getDb();
                        StringField keyField = resource.getKeyFields()[0].asStringField();
                        String uuid = pathParameters.getNotEmpty(UrlType.uuid);
                        return retrieveResource(resource, db, keyField, uuid);
                    })
                    .declareReturnType(resource);

            onUrl.delete(null, (body, pathParameters, queryParameters) -> {
                SqlConnection db = sqlService.getDb();
                StringField keyField = resource.getFieldWithAnnotation(KeyField.UNIQUE_KEY).asStringField();
                String uuid = pathParameters.getNotEmpty(UrlType.uuid);
                try (SqlRequest deleteRequest = db.getDeleteRequest(resource, Constraints.equal(keyField, uuid))) {
                    deleteRequest.run();
                } finally {
                    db.commitAndClose();
                }
                return CompletableFuture.completedFuture(null);
            });
        }

        // we register now the entry point for graphQL.

        // we generate the graphql schema.
        // to load it in the graphql library to response to query on schema
        if (graphQLSchemaType.isPresent()) {
            // Create a virtual thread as graphql code is mostly db access.
            ThreadFactory factory = Thread.ofVirtual().name("GQL").factory();

            // create a globs graphql builder where we register loader to fetch db data.
            GQLGlobCallerBuilder<DbContext> gqlGlobCallerBuilder = new GQLGlobCallerBuilder<DbContext>(
                    newThreadPerTaskExecutor(factory)
            );

            // loader from root (so without parent  ).

            GlobField queryField = graphQLSchemaType.get().getField("query").asGlobField();

            GlobType queryType = queryField.getTargetType();

            Set<GlobType> subObject = new HashSet<>();
            for (Field field : queryType.getFields()) {
                field.findOptAnnotation(GQLQueryParam.KEY)
                        .filter(annotation -> annotation.get(GQLQueryParam.name).equals(EntityQuery.TYPE.getName()))
                        .ifPresent(glob -> {
                            GlobField globField = field.asGlobField();
                            GlobType targetType = globField.getTargetType();
                            subObject.add(targetType);

                            String dbTypeName = targetType.getAnnotation(DbTarget.KEY).get(DbTarget.dbResource);
                            GlobType dbType = resources.getType(dbTypeName);
                            StringField uuidField = dbType.getFieldWithAnnotation(KeyField.UNIQUE_KEY).asStringField();

                            gqlGlobCallerBuilder.registerLoader(globField, (gqlField, callContext, parents) -> {
                                load(gqlField, parents, EntityQuery.uuid, dbType, uuidField, callContext);
                                return CompletableFuture.completedFuture(null);
                            });
                        });
                field.findOptAnnotation(GQLQueryParam.KEY)
                        .filter(annotation -> annotation.get(GQLQueryParam.name).equals(SearchQuery.TYPE.getName()))
                        .ifPresent(glob -> {
                            GlobArrayField globField = field.asGlobArrayField();
                            GlobType targetType = globField.getTargetType();
                            subObject.add(targetType);
                            String dbTypeName = targetType.getAnnotation(DbTarget.KEY).get(DbTarget.dbResource);
                            GlobType dbType = resources.getType(dbTypeName);
                            StringField[] searchableFields = dbType.getFieldsWithAnnotation(Searchable.KEY).stream().map(Field::asStringField).toArray(StringField[]::new);

                            gqlGlobCallerBuilder.registerLoader(globField, (gqlField, callContext, parents) -> {
                                search(gqlField, parents, callContext, dbType, searchableFields);
                                return CompletableFuture.completedFuture(null);
                            });
                        });
            }
            List<GlobType> remainning = new ArrayList<>(subObject);
            while (!remainning.isEmpty()) {
                GlobType glObjectType = remainning.removeFirst();
                for (Field field : glObjectType.getFields()) {

                    Glob linkAnnotation = field.findAnnotation(Link.KEY);
                    if (linkAnnotation != null) {
                        GlobType targetType = field instanceof GlobField ? field.asGlobField().getTargetType() :
                                field instanceof GlobArrayField ? field.asGlobArrayField().getTargetType() : null;
                        if (targetType != null) {
                            if (subObject.add(targetType)) {
                                remainning.add(targetType);
                            }
                            String dbSourceTypeName = glObjectType.getAnnotation(DbTarget.KEY).get(DbTarget.dbResource);
                            String dbTargetTypeName = targetType.getAnnotation(DbTarget.KEY).get(DbTarget.dbResource);
                            GlobType dbSourceType = resources.getType(dbSourceTypeName);
                            GlobType dbTargetType = resources.getType(dbTargetTypeName);
                            Field dbSourceField = dbSourceType.getField(linkAnnotation.get(Link.fromField));
                            Field dbTargetTypeField = dbTargetType.getField(linkAnnotation.get(Link.toField));
                            gqlGlobCallerBuilder.registerLoader(field, (gqlField, callContext, parents) ->
                                    loadFromParent(parents, dbSourceField.asStringField(), callContext, dbTargetTypeField.asStringField()));
                        } else {
                            throw new RuntimeException("Field not");
                        }
                    }

                    if (field.hasAnnotation(IsConnection.KEY)) {
                        GlobField connectionField = field.asGlobField();
                        GlobType gqlTargetField = connectionField.getTargetType().getField("edges").asGlobArrayField().getTargetType()
                                .getField("node").asGlobField().getTargetType();
                        GlobType targetResource = resources.getType(gqlTargetField.getAnnotation(DbTarget.KEY).get(DbTarget.dbResource));
                        StringField uuid = targetResource.getKeyFields()[0].asStringField();
                        gqlGlobCallerBuilder.registerConnection(connectionField, (gqlField, callContext, parents) -> {
                            parents.forEach(p ->
                                    ConnectionBuilder.withDbKey(uuid)
                                            .withParam(Parameter.EMPTY, Parameter.after,
                                                    Parameter.first, Parameter.before,
                                                    Parameter.last, Parameter.skip)
                                            .withOrder(Parameter.orderBy, Parameter.order)
                                            .scanAll(gqlField, p, null, callContext.dbConnection));
                            return CompletableFuture.completedFuture(null);
                        }, uuid, Parameter.orderBy);
                    }
                }
            }

            SchemaParser schemaParser = new SchemaParser();
            SchemaGenerator schemaGenerator = new SchemaGenerator();
            GlobSchemaGenerator globSchemaGenerator = new GlobSchemaGenerator(graphQLSchemaType.get(), new DefaultGlobModel(Parameter.TYPE, EntityQuery.TYPE, SearchQuery.TYPE));
            final String s = globSchemaGenerator.generateAll();
            LOGGER.info("Schema is\n" + s);
            final TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(s);
            GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, RuntimeWiring.MOCKED_WIRING);
            GraphQL gql = GraphQL.newGraphQL(graphQLSchema).build();

            GQLGlobCaller<DbContext> gqlGlobCaller =
                    gqlGlobCallerBuilder.build(graphQLSchemaType.get(), new DefaultGlobModel(Parameter.TYPE, EntityQuery.TYPE, SearchQuery.TYPE));
            httpServerRegister.register("/api/graphql", null)
                    .post(GraphQlRequest.TYPE, null, null, new HttpTreatmentWithHeader() {
                        final Gson gson = new Gson();

                        public CompletableFuture<Glob> consume(Glob body, Glob url, Glob queryParameters, Glob header) throws Exception {
                            String query = body.get(GraphQlRequest.query);

                            // hack to response to query on schema.
                            if (query.contains("__schema")) {
                                final ExecutionResult execute = gql.execute(query);
                                final Map<String, Object> stringObjectMap = execute.toSpecification();
                                final String s1 = gson.toJson(stringObjectMap);
                                return CompletableFuture.completedFuture(GlobHttpContent.TYPE.instantiate()
                                        .set(GlobHttpContent.content, s1.getBytes(StandardCharsets.UTF_8)));
                            }

                            // manage variables.
                            String v = body.get(GraphQlRequest.variables);
                            Map<String, String> variables = new HashMap<>();
                            if (Strings.isNotEmpty(v)) {
                                JsonReader jsonReader = new JsonReader(new StringReader(v));
                                JsonElement jsonElement = JsonParser.parseReader(jsonReader);
                                JsonObject asJsonObject = jsonElement.getAsJsonObject();
                                Set<Map.Entry<String, JsonElement>> entries = asJsonObject.entrySet();
                                for (Map.Entry<String, JsonElement> entry : entries) {
                                    variables.put(entry.getKey(), gson.toJson(entry.getValue()));
                                }
                            }

                            // handle graphql request.
                            DbContext gqlContext = new DbContext(sqlService.getAutoCommitDb());
                            return gqlGlobCaller.query(query, variables, gqlContext)
                                    .thenApply(glob -> GraphQlResponse.TYPE.instantiate().set(GraphQlResponse.data, GSonUtils.encode(glob, false)))
                                    .handle((response, throwable) -> {
                                        gqlContext.dbConnection.commitAndClose();
                                        if (throwable != null) {
                                            return GraphQlResponse.TYPE.instantiate()
                                                    .set(GraphQlResponse.errorMessage, throwable.getMessage());
                                        } else {
                                            return response;
                                        }
                                    });
                        }
                    });
        }

        // register openAPI entrypoint on /api
        httpServerRegister.registerOpenApi();

        // register to and start apache server.
        Pair<HttpServer, Integer> httpServerIntegerPair =
                httpServerRegister.startAndWaitForStartup(
                        ServerBootstrap.bootstrap()
                                .setIOReactorConfig(IOReactorConfig.custom().setSoReuseAddress(true).build())
                                .setListenerPort(argument.get(ArgumentType.port, 4000)));
        System.out.println("Listen on port: " + httpServerIntegerPair.getSecond());
        synchronized (System.out) {
            System.out.wait();
        }
    }

    private static void search(GqlField gqlField, List<OnLoad> parents, DbContext callContext, GlobType dbType, StringField... fields) {
        Optional<String> searchValue = gqlField.field().parameters().map(SearchQuery.search);
        try (SelectQuery query = callContext.dbConnection.getQueryBuilder(dbType,
                        searchValue.map(s -> Constraints.or(
                                        Arrays.stream(fields).map(f -> Constraints.containsIgnoreCase(f, s)).toArray(Constraint[]::new)))
                                .orElse(null)
                )
                .selectAll()
                .getQuery()) {
            try (Stream<Glob> globStream = query.executeAsGlobStream()) {
                globStream.forEach(parents.getFirst().onNew()::push);
            }
        }
    }

    private static CompletableFuture<Void> loadFromParent(List<OnLoad> parents, StringField mainClassUUID, DbContext dbContext, StringField uuid) {
        Map<String, List<OnLoad>> toQuery =
                parents.stream().collect(
                        Collectors.groupingBy(onLoad ->
                                onLoad.parent().get(mainClassUUID)));
        SqlConnection db = dbContext.dbConnection;
        try (SelectQuery query = db.getQueryBuilder(uuid.getGlobType(), Constraints.in(uuid, toQuery.keySet()))
                .selectAll()
                .getQuery()) {
            try (Stream<Glob> globStream = query.executeAsGlobStream()) {
                globStream.forEach(d -> toQuery.getOrDefault(d.get(uuid), List.of())
                        .forEach(onLoad -> onLoad.onNew().push(d)));
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<Glob> retrieveResource(GlobType resource, SqlConnection db, StringField keyField, String uuid) {
        Glob createdData;
        // sql select * from 'resource' where uuid='uuidValue'
        try (SelectQuery query = db.getQueryBuilder(resource, Constraints.equal(keyField, uuid))
                .selectAll()
                .getQuery()) {
            createdData = query.executeUnique();
        } finally {
            db.commitAndClose();
        }
        return CompletableFuture.completedFuture(createdData);
    }

    private static void load(GqlField gqlField, List<OnLoad> parents, StringField paramUUIDField, GlobType dbType,
                             StringField dbUUID, DbContext dbContext) {
        String uuid = gqlField.field().parameters().map(paramUUIDField).orElseThrow();
        SqlConnection db = dbContext.dbConnection;
        try (SelectQuery query = db.getQueryBuilder(dbType, Constraints.equal(dbUUID, uuid))
                .selectAll()
                .getQuery()) {
            parents.getFirst()
                    .onNew()
                    .push(query.executeUnique());
        }
    }

    public static class UrlType {
        public static GlobType TYPE;

        @FieldName_("uuid")
        public static StringField uuid;

        static {
            GlobTypeLoaderFactory.create(UrlType.class).load();
        }
    }

    public static class ArgumentType {
        public static GlobType TYPE;

        @DefaultString_("jdbc:hsqldb:mem:db")
        public static StringField dbUrl;

        @DefaultString_("sa")
        public static StringField user;

        @DefaultString_("")
        public static StringField password;

        public static StringField model;

        public static IntegerField port;

        static {
            GlobTypeLoaderFactory.create(ArgumentType.class).load();
        }
    }


    public static class Parameter {
        public static GlobType TYPE;

        @InitUniqueGlob
        public static Glob EMPTY;

        public static IntegerField first;

        public static StringField after;

        public static IntegerField last;

        public static StringField before;

        public static IntegerField skip;

        public static StringField order; // asc, desc ?

        public static StringField orderBy; //

        static {
            GlobTypeLoaderFactory.create(Parameter.class).load();
        }
    }

    public static class GraphQlRequest {
        public static GlobType TYPE;

        public static StringField query;

        @IsJsonContent_
        public static StringField variables;

        static {
            GlobTypeLoaderFactory.create(GraphQlRequest.class).load();
        }
    }

    static class DbContext implements GQLGlobCaller.GQLContext {
        final SqlConnection dbConnection;

        public DbContext(SqlConnection dbConnection) {
            this.dbConnection = dbConnection;
        }
    }

    public static class Model {
        public static GlobType TYPE;

        @IsJsonContent_
        public static StringArrayField dbTypes;

        @IsJsonContent_
        public static StringField graphqlTypes;

        static {
            GlobTypeLoaderFactory.create(Model.class).load();
        }
    }

    public static class SearchQuery {
        public static GlobType TYPE;

        public static StringField search;

        static {
            GlobTypeLoaderFactory.create(SearchQuery.class).load();
        }
    }

    public static class EntityQuery {
        public static GlobType TYPE;

        public static StringField uuid;

        static {
            GlobTypeLoaderFactory.create(EntityQuery.class).load();
        }
    }

}
