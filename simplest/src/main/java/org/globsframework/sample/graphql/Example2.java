package org.globsframework.sample.graphql;

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
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeLoaderFactory;
import org.globsframework.core.metamodel.annotations.*;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.streams.DbStream;
import org.globsframework.core.streams.accessors.LongAccessor;
import org.globsframework.core.utils.Strings;
import org.globsframework.core.utils.collections.Pair;
import org.globsframework.graphql.GQLGlobCaller;
import org.globsframework.graphql.GQLGlobCallerBuilder;
import org.globsframework.graphql.GlobSchemaGenerator;
import org.globsframework.graphql.OnLoad;
import org.globsframework.graphql.db.ConnectionBuilder;
import org.globsframework.graphql.model.GQLMandatory_;
import org.globsframework.graphql.model.GQLPageInfo;
import org.globsframework.graphql.model.GQLQueryParam_;
import org.globsframework.graphql.model.GraphQlResponse;
import org.globsframework.graphql.parser.GqlField;
import org.globsframework.http.GlobHttpContent;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.json.GSonUtils;
import org.globsframework.json.annottations.IsJsonContent_;
import org.globsframework.sql.*;
import org.globsframework.sql.annotations.DbTableName_;
import org.globsframework.sql.constraints.Constraint;
import org.globsframework.sql.constraints.Constraints;
import org.globsframework.sql.drivers.jdbc.DataSourceSqlService;
import org.globsframework.sql.drivers.jdbc.DbType;
import org.globsframework.sql.drivers.jdbc.MappingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

/*
start with following argument

 --dbUrl jdbc:hsqldb:file:./db/ --user sa --password ""
or
--dbUrl jdbc:postgresql://localhost/postgres --user postgres --password xxxx

The code create and populates empty db.

Then you can query db with graphQl:
curl 'http://localhost:4000/graphql' --data-binary '{"query":"{\n  professors: professors{\n   uuid\n    firstName\n    lastName\n    mainClasses{\n      name\n      students{\n        totalCount\n      }\n    }\n  }\n  allClasses: classes{\n     name\n     students{\n      totalCount\n       edges{\n         node{\n           firstName\n           lastName\n         }\n       }\n     }\n   }\n}","variables":{}}'

The code expose on default port 4000 a
REST api route /api/{class, student, professor} en post/put/get
OPEN API under /api/openapi
GRAPHQL route under /graphql
 */

public class Example2 {

    public static final Logger LOGGER = LoggerFactory.getLogger(Example2.class);

    public static void main(String[] args) throws InterruptedException {
        Gson gson = new Gson();
        Glob argument = ParseCommandLine.parse(ArgumentType.TYPE, args);

        DbType dbType = DbType.fromString(argument.getNotEmpty(ArgumentType.dbUrl));
        HikariConfig configuration = new HikariConfig();
        configuration.setUsername(argument.getNotEmpty(ArgumentType.user));
        configuration.setPassword(argument.get(ArgumentType.password));
        configuration.setJdbcUrl(argument.getNotEmpty(ArgumentType.dbUrl));
        SqlService sqlService = new DataSourceSqlService(
                MappingHelper.get(dbType), new HikariDataSource(configuration), dbType);

        GlobType[] resources = { DbStudentType.TYPE, DbProfessorType.TYPE, DbClassType.TYPE };
        {
            SqlConnection db = sqlService.getDb();
            Arrays.asList(resources).forEach(db::createTable);
            db.commitAndClose();
        }
        {
            populate(sqlService);
        }

        ThreadFactory factory = Thread.ofVirtual().name("GQL").factory();

        GQLGlobCallerBuilder<DbContext> gqlGlobCallerBuilder = new GQLGlobCallerBuilder<DbContext>(
                newThreadPerTaskExecutor(factory)
        );

        gqlGlobCallerBuilder.registerLoader(QueryType.professor, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbProfessorType.TYPE, DbProfessorType.uuid, callContext);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.class_, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbClassType.TYPE, DbClassType.uuid, callContext);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.student, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbStudentType.TYPE, DbStudentType.uuid, callContext);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.professors, (gqlField, callContext, parents) -> {
            search(gqlField, parents, callContext, DbProfessorType.TYPE, DbProfessorType.firstName, DbProfessorType.lastName);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.classes, (gqlField, callContext, parents) -> {
            search(gqlField, parents, callContext, DbClassType.TYPE, DbClassType.name);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.students, (gqlField, callContext, parents) -> {
            search(gqlField, parents, callContext, DbStudentType.TYPE, DbStudentType.firstName, DbStudentType.lastName);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(GQLProfessor.mainClasses, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbProfessorType.uuid, callContext, DbClassType.principalProfessorUUID, DbClassType.TYPE));

        gqlGlobCallerBuilder.registerLoader(GQLClass.principalProfessor, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbClassType.principalProfessorUUID, callContext, DbProfessorType.uuid, DbProfessorType.TYPE));

        gqlGlobCallerBuilder.registerConnection(GQLClass.students, (gqlField, callContext, parents) -> {
            parents.forEach(p ->
                    ConnectionBuilder.withDbKey(DbStudentType.uuid)
                            .withParam(Parameter.EMPTY, Parameter.after,
                                    Parameter.first, Parameter.before,
                                    Parameter.last, Parameter.skip)
                            .withOrder(Parameter.orderBy, Parameter.order)
                            .scanAll(gqlField, p, null, callContext.dbConnection));
            return CompletableFuture.completedFuture(null);
        }, DbStudentType.uuid, Parameter.orderBy);

        gqlGlobCallerBuilder.registerLoader(GQLStudent.class_, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbStudentType.mainClassUUID, callContext, DbClassType.uuid, DbClassType.TYPE));

        final HttpServerRegister httpServerRegister = new HttpServerRegister("EstablishmentServer/0.1");

        for (GlobType resource : resources) {
            httpServerRegister.register("/api/" + resource.getName(), null)
                    .post(resource, null, (body, pathParameters, queryParameters) -> {

                        StringField keyField = resource.getKeyFields()[0].asStringField();
                        String uuid = UUID.randomUUID().toString();
                        SqlConnection db = sqlService.getDb();
                        try {
                            CreateBuilder createBuilder = db.getCreateBuilder(resource);

                            for (Field field : resource.getFields()) {
                                if (!field.isKeyField()) {
                                    body.getOptValue(field).ifPresent(v -> createBuilder.setObject(field, v));
                                }
                            }

                            createBuilder.set(keyField, uuid);
                            try (SqlRequest insertRequest = createBuilder.getRequest()) {
                                insertRequest.run();
                            }
                        } finally {
                            db.commitAndClose();
                        }

                        return retrieveResource(resource, db, keyField, uuid);
                    })
                    .declareReturnType(resource);

            httpServerRegister.register("/api/" + resource.getName() + "/{uuid}", UrlType.TYPE)
                    .put(resource, null, (body, pathParameters, queryParameters) -> {

                        SqlConnection db = sqlService.getDb();
                        StringField keyField = resource.getKeyFields()[0].asStringField();
                        String uuid = pathParameters.getNotEmpty(UrlType.uuid);
                        UpdateBuilder updateBuilder = db.getUpdateBuilder(resource, Constraints.equal(keyField, uuid));

                        for (Field field : resource.getFields()) {
                            if (!field.isKeyField()) {
                                body.getOptValue(field).ifPresent(v -> updateBuilder.updateUntyped(field, v));
                            }
                        }

                        try (SqlRequest insertRequest = updateBuilder.getRequest()) {
                            insertRequest.run();
                        }
                        db.commit();

                        return retrieveResource(resource, db, keyField, uuid);
                    })
                    .declareReturnType(resource);

            httpServerRegister.register("/api/" + resource.getName() + "/{uuid}", UrlType.TYPE)
                    .get(null, (body, pathParameters, queryParameters) -> {
                        SqlConnection db = sqlService.getDb();
                        StringField keyField = resource.getKeyFields()[0].asStringField();
                        String uuid = pathParameters.getNotEmpty(UrlType.uuid);
                        return retrieveResource(resource, db, keyField, uuid);
                    })
                    .declareReturnType(resource);
        }

        SchemaParser schemaParser = new SchemaParser();
        SchemaGenerator schemaGenerator = new SchemaGenerator();
        GlobSchemaGenerator globSchemaGenerator = new GlobSchemaGenerator(SchemaType.TYPE, new DefaultGlobModel(Parameter.TYPE, EntityQuery.TYPE, SearchQuery.TYPE));
        final String s = globSchemaGenerator.generateAll();
        LOGGER.info("Schema is " + s);
        final TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(s);
        GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, RuntimeWiring.MOCKED_WIRING);
        GraphQL gql = GraphQL.newGraphQL(graphQLSchema).build();


        GQLGlobCaller<DbContext> gqlGlobCaller =
                gqlGlobCallerBuilder.build(SchemaType.TYPE, new DefaultGlobModel(Parameter.TYPE, EntityQuery.TYPE, SearchQuery.TYPE));
        httpServerRegister.register("/graphql", null)
                .post(GraphQlRequest.TYPE, null, null, (body, url, queryParameters, header) -> {
                    String query = body.get(GraphQlRequest.query);
                    if (query.contains("__schema")) {
                        final ExecutionResult execute = gql.execute(query);
                        final Map<String, Object> stringObjectMap = execute.toSpecification();
                        final String s1 = gson.toJson(stringObjectMap);
                        return CompletableFuture.completedFuture(GlobHttpContent.TYPE.instantiate()
                                .set(GlobHttpContent.content, s1.getBytes(StandardCharsets.UTF_8)));
                    }
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
                });
        httpServerRegister.registerOpenApi();

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

    private static void populate(SqlService sqlService) {
        final SqlConnection db = sqlService.getDb();
        final SelectBuilder queryBuilder = db.getQueryBuilder(DbStudentType.TYPE);
        final LongAccessor count = queryBuilder.count(DbStudentType.uuid);
        try (SelectQuery query = queryBuilder.getQuery()) {
            final DbStream execute = query.execute();
            if (execute.next() && count.getLong() != 0) {
                db.commitAndClose();
                return;
            }
        }

        Glob prof_1 = DbProfessorType.TYPE.instantiate().set(DbProfessorType.firstName, "Jones").set(DbProfessorType.lastName, "David")
                .set(DbProfessorType.uuid, UUID.randomUUID().toString());
        Glob prof_2 = DbProfessorType.TYPE.instantiate().set(DbProfessorType.firstName, "Williams").set(DbProfessorType.lastName, "Jessica")
                .set(DbProfessorType.uuid, UUID.randomUUID().toString());
        db.populate(Arrays.asList(prof_1, prof_2));
        final MutableGlob class1_a = DbClassType.TYPE.instantiate().set(DbClassType.name, "1-a")
                .set(DbClassType.principalProfessorUUID, prof_1.get(DbProfessorType.uuid))
                .set(DbClassType.uuid, UUID.randomUUID().toString());
        final MutableGlob class1_b = DbClassType.TYPE.instantiate().set(DbClassType.name, "2-a")
                .set(DbClassType.principalProfessorUUID, prof_2.get(DbProfessorType.uuid))
                .set(DbClassType.uuid, UUID.randomUUID().toString());
        db.populate(Arrays.asList(class1_a, class1_b));
        String[] surnames = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Miller", "Davis", "Garcia", "Rodriguez",
                "Wilson", "Martinez", "Anderson", "Taylor", "Thomas", "Moore", "Jackson", "White", "Harris", "Thompson", "Lewis"};
        String[] firstNames = {"James", "John", "Robert", "Michael", "William", "David", "Richard", "Joseph", "Charles",
                "Thomas", "Mary", "Patricia", "Jennifer", "Linda", "Elizabeth", "Barbara", "Susan", "Jessica", "Sarah", "Karen"};
        List<Glob> globs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            globs.add(DbStudentType.TYPE.instantiate()
                    .set(DbStudentType.uuid, UUID.randomUUID().toString())
                    .set(DbStudentType.firstName, firstNames[i])
                    .set(DbStudentType.lastName, surnames[i])
                    .set(DbStudentType.mainClassUUID, i < 10 ? class1_a.get(DbClassType.uuid) : class1_b.get(DbClassType.uuid)));
        }
        db.populate(globs);
        db.commitAndClose();
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

    private static CompletableFuture<Void> loadFromParent(List<OnLoad> parents, StringField mainClassUUID, DbContext dbContext, StringField uuid, GlobType dbType) {
        Map<String, List<OnLoad>> toQuery =
                parents.stream().collect(
                        Collectors.groupingBy(onLoad ->
                                onLoad.parent().get(mainClassUUID)));
        SqlConnection db = dbContext.dbConnection;
        try (SelectQuery query = db.getQueryBuilder(dbType, Constraints.in(uuid, toQuery.keySet()))
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


    public static class DbClassType {
        @DbTableName_("classes")
        public static GlobType TYPE;

        @KeyField_
        public static StringField uuid;

        public static StringField name;

        public static StringField principalProfessorUUID;

        static {
            GlobTypeLoaderFactory.create(DbClassType.class, "class").load();
        }
    }

    public static class DbProfessorType {
        @DbTableName_("professors")
        public static GlobType TYPE;

        @KeyField_
        public static StringField uuid;

        public static StringField firstName;

        public static StringField lastName;

        static {
            GlobTypeLoaderFactory.create(DbProfessorType.class, "professor").load();
        }
    }

    public static class DbStudentType {
        @DbTableName_("students")
        public static GlobType TYPE;

        @KeyField_
        public static StringField uuid;

        public static StringField firstName;

        public static StringField lastName;

        public static StringField mainClassUUID;

        static {
            GlobTypeLoaderFactory.create(DbStudentType.class, "student").load();
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

        @DefaultString_("jdbc:hsqldb:file:./db/")
        public static StringField dbUrl;

        @DefaultString_("sa")
        public static StringField user;

        @DefaultString_("")
        public static StringField password;

        public static IntegerField port;

        static {
            GlobTypeLoaderFactory.create(ArgumentType.class).load();
        }
    }

    public static class SchemaType {
        public static GlobType TYPE;

        @Target(QueryType.class)
        public static GlobField query;

        static {
            GlobTypeLoaderFactory.create(SchemaType.class).load();
        }
    }

    public static class QueryType {
        public static GlobType TYPE;

        @GQLQueryParam_(SearchQuery.class)
        @Target(GQLProfessor.class)
        public static GlobArrayField professors;

        @GQLQueryParam_(SearchQuery.class)
        @Target(GQLClass.class)
        @FieldName_("classes")
        public static GlobArrayField classes;

        @GQLQueryParam_(SearchQuery.class)
        @Target(GQLStudent.class)
        public static GlobArrayField students;

        @GQLQueryParam_(EntityQuery.class)
        @Target(GQLProfessor.class)
        public static GlobField professor;

        @GQLQueryParam_(EntityQuery.class)
        @Target(GQLClass.class)
        @FieldName_("class")
        public static GlobField class_;

        @GQLQueryParam_(EntityQuery.class)
        @Target(GQLStudent.class)
        public static GlobField student;

        static {
            GlobTypeLoaderFactory.create(QueryType.class).load();
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

    public static class GQLClass {
        public static GlobType TYPE;

        public static StringField uuid;

        public static StringField name;

        @Target(GQLProfessor.class)
        public static GlobField principalProfessor;

        @Target(StudentConnection.class)
        @GQLQueryParam_(Parameter.class)
        public static GlobField students;

        static {
            GlobTypeLoaderFactory.create(GQLClass.class).load();
        }
    }

    public static class GQLStudent {
        public static GlobType TYPE;

        public static StringField uuid;

        public static StringField firstName;

        public static StringField lastName;

        @Target(GQLClass.class)
        @FieldName_("class")
        public static GlobField class_;

        static {
            GlobTypeLoaderFactory.create(GQLStudent.class).load();
        }
    }

    public static class GQLProfessor {
        public static GlobType TYPE;

        public static StringField uuid;

        public static StringField firstName;

        public static StringField lastName;

        @Target(GQLClass.class)
        public static GlobArrayField mainClasses;

        static {
            GlobTypeLoaderFactory.create(GQLProfessor.class).load();
        }
    }

    public static class StudentConnection {
        public static GlobType TYPE;

        public static IntegerField totalCount;

        @Target(StudentHedge.class)
        public static GlobArrayField edges;

        @Target(GQLPageInfo.class)
        @GQLMandatory_
        public static GlobField pageInfo;

        static {
            GlobTypeLoaderFactory.create(StudentConnection.class).load();
        }
    }

    public static class StudentHedge {
        public static GlobType TYPE;

        @Target(GQLStudent.class)
        public static GlobField node;

        static {
            GlobTypeLoaderFactory.create(StudentHedge.class).load();
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
}
