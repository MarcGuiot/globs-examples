package org.globsframework.sample.graphql;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
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
import org.globsframework.core.metamodel.annotations.FieldNameAnnotation;
import org.globsframework.core.metamodel.annotations.InitUniqueGlob;
import org.globsframework.core.metamodel.annotations.KeyField;
import org.globsframework.core.metamodel.annotations.Target;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
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
import org.globsframework.json.annottations.IsJsonContentAnnotation;
import org.globsframework.sql.*;
import org.globsframework.sql.annotations.typed.TargetTypeNameAnnotation;
import org.globsframework.sql.constraints.Constraint;
import org.globsframework.sql.constraints.Constraints;
import org.globsframework.sql.drivers.jdbc.JdbcSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

/*
Expose api route /student en post
Expose un route /api/openapi
create a table
insert in the table and return the newly student.

 */

public class Example2 {
    public static final Logger LOGGER = LoggerFactory.getLogger(Example2.class);

    public static void main(String[] args) throws InterruptedException {
        Gson gson = new Gson();
        Glob argument = ParseCommandLine.parse(ArgumentType.TYPE, args);

        SqlService sqlService = new JdbcSqlService(
                argument.getNotEmpty(ArgumentType.dbUrl),
                argument.getNotEmpty(ArgumentType.user),
                argument.get(ArgumentType.password));

        GlobType[] resources = {DbStudentType.TYPE, DbProfessorType.TYPE, DbClassType.TYPE};
        {
            SqlConnection db = sqlService.getDb();
            Arrays.asList(resources).forEach(db::createTable);
            db.commitAndClose();
        }

        ThreadFactory factory = Thread.ofVirtual().name("GQL").factory();

        GQLGlobCallerBuilder<GQLGlobCaller.GQLContext> gqlGlobCallerBuilder = new GQLGlobCallerBuilder<>(
                newThreadPerTaskExecutor(factory)
        );

        gqlGlobCallerBuilder.registerLoader(QueryType.professor, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbProfessorType.TYPE, DbProfessorType.uuid, sqlService);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.class_, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbClassType.TYPE, DbClassType.uuid, sqlService);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.student, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbStudentType.TYPE, DbStudentType.uuid, sqlService);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.professors, (gqlField, callContext, parents) -> {
            search(gqlField, parents, sqlService, DbProfessorType.TYPE, DbProfessorType.firstName, DbProfessorType.lastName);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.classes, (gqlField, callContext, parents) -> {
            search(gqlField, parents, sqlService, DbClassType.TYPE, DbClassType.name);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.students, (gqlField, callContext, parents) -> {
            search(gqlField, parents, sqlService, DbStudentType.TYPE, DbStudentType.firstName, DbStudentType.lastName);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(GQLProfessor.mainClasses, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbProfessorType.uuid, sqlService, DbClassType.principalProfessorUUID, DbClassType.TYPE));

        gqlGlobCallerBuilder.registerLoader(GQLClass.principalProfessor, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbClassType.principalProfessorUUID, sqlService, DbProfessorType.uuid, DbProfessorType.TYPE));

        gqlGlobCallerBuilder.registerConnection(GQLClass.students, (gqlField, callContext, parents) -> {
            SqlConnection db = sqlService.getDb();
            try {
                parents.forEach(p ->
                        ConnectionBuilder.withDbKey(DbStudentType.uuid)
                                .withParam(Parameter.EMPTY, Parameter.after,
                                        Parameter.first, Parameter.before,
                                        Parameter.last, Parameter.skip)
                                .withOrder(Parameter.orderBy, Parameter.order)
                                .scanAll(gqlField, p, null, db));
            } finally {
                db.commitAndClose();
            }
            return CompletableFuture.completedFuture(null);
        }, DbStudentType.uuid, Parameter.orderBy);

        gqlGlobCallerBuilder.registerLoader(GQLStudent.class_, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbStudentType.mainClassUUID, sqlService, DbClassType.uuid, DbClassType.TYPE));

        final HttpServerRegister httpServerRegister = new HttpServerRegister("EstablishmentServer/0.1");

        for (GlobType resource : resources) {
            httpServerRegister.register("/api/resources/" + resource.getName() + "/action/new", null)
                    .post(resource, null, (body, pathParameters, queryParameters) -> {

                        SqlConnection db = sqlService.getDb();
                        CreateBuilder createBuilder = db.getCreateBuilder(resource);

                        for (Field field : resource.getFields()) {
                            if (!field.isKeyField()) {
                                body.getOptValue(field).ifPresent(v -> createBuilder.setObject(field, v));
                            }
                        }

                        StringField keyField = resource.getKeyFields()[0].asStringField();
                        String uuid = UUID.randomUUID().toString();
                        createBuilder.set(keyField, uuid);
                        try (SqlRequest insertRequest = createBuilder.getRequest()) {
                            insertRequest.run();
                        }
                        db.commit();

                        return retrieveResource(resource, db, keyField, uuid);
                    })
                    .declareReturnType(resource);

            httpServerRegister.register("/api/resources/" + resource.getName() + "/records/{uuid}/action/edit", UrlType.TYPE)
                    .post(resource, null, (body, pathParameters, queryParameters) -> {

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

            httpServerRegister.register("/api/resources/" + resource.getName() + "/records/{uuid}/action/show", UrlType.TYPE)
                    .post(resource, null, (body, pathParameters, queryParameters) -> {

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


        GQLGlobCaller<GQLGlobCaller.GQLContext> gqlGlobCaller = gqlGlobCallerBuilder.build(SchemaType.TYPE, new DefaultGlobModel(Parameter.TYPE, EntityQuery.TYPE, SearchQuery.TYPE));
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
                    return gqlGlobCaller.query(query, variables, new GQLGlobCaller.GQLContext() {
                            })
                            .thenApply(glob -> GraphQlResponse.TYPE.instantiate().set(GraphQlResponse.data, GSonUtils.encode(glob, false)))
                            .handle((response, throwable) -> {
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
                                .setListenerPort(argument.get(ArgumentType.port, 3000)));
        System.out.println("Listen on port: " + httpServerIntegerPair.getSecond());
        synchronized (System.out) {
            System.out.wait();
        }
    }

    private static void search(GqlField gqlField, List<OnLoad> parents, SqlService sqlService, GlobType dbType, StringField... fields) {
        Optional<String> searchValue = gqlField.field().parameters().map(SearchQuery.search);
        SqlConnection db = sqlService.getDb();
        try (SelectQuery query = db.getQueryBuilder(dbType,
                        searchValue.map(s -> Constraints.or(
                                        Arrays.stream(fields).map(f -> Constraints.containsIgnoreCase(f, s)).toArray(Constraint[]::new)))
                                .orElse(null)
                )
                .selectAll()
                .getQuery()) {
            query.executeAsGlobStream().forEach(parents.getFirst().onNew()::push);
        } finally {
            db.commitAndClose();
        }
    }

    private static CompletableFuture<Void> loadFromParent(List<OnLoad> parents, StringField mainClassUUID, SqlService sqlService, StringField uuid, GlobType dbType) {
        Map<String, List<OnLoad>> toQuery =
                parents.stream().collect(
                        Collectors.groupingBy(onLoad ->
                                onLoad.parent().get(mainClassUUID)));
        SqlConnection db = sqlService.getDb();
        try (SelectQuery query = db.getQueryBuilder(dbType, Constraints.in(uuid, toQuery.keySet()))
                .selectAll()
                .getQuery()) {
            query
                    .executeAsGlobStream().forEach(d -> toQuery.getOrDefault(d.get(uuid), List.of())
                            .forEach(onLoad -> onLoad.onNew().push(d)));
        } finally {
            db.commitAndClose();
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
                             StringField dbUUID, SqlService sqlService) {
        String uuid = gqlField.field().parameters().map(paramUUIDField).orElseThrow();
        SqlConnection db = sqlService.getDb();
        try (SelectQuery query = db.getQueryBuilder(dbType, Constraints.equal(dbUUID, uuid))
                .selectAll()
                .getQuery()) {
            parents.getFirst()
                    .onNew()
                    .push(query.executeUnique());
        } finally {
            db.commitAndClose();
        }
    }


    public static class DbClassType {
        @TargetTypeNameAnnotation("classes")
        public static GlobType TYPE;

        @KeyField
        public static StringField uuid;

        public static StringField name;

        public static StringField principalProfessorUUID;

        static {
            GlobTypeLoaderFactory.create(DbClassType.class, "class").load();
        }
    }

    public static class DbProfessorType {
        @TargetTypeNameAnnotation("professors")
        public static GlobType TYPE;

        @KeyField
        public static StringField uuid;

        public static StringField firstName;

        public static StringField lastName;

        static {
            GlobTypeLoaderFactory.create(DbProfessorType.class, "professor").load();
        }
    }

    public static class DbStudentType {
        @TargetTypeNameAnnotation("students")
        public static GlobType TYPE;

        @KeyField
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

        @FieldNameAnnotation("uuid")
        public static StringField uuid;

        static {
            GlobTypeLoaderFactory.create(UrlType.class).load();
        }
    }

    public static class ArgumentType {
        public static GlobType TYPE;

        public static StringField dbUrl;

        public static StringField user;

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
        @FieldNameAnnotation("classes")
        public static GlobArrayField classes;

        @GQLQueryParam_(SearchQuery.class)
        @Target(GQLStudent.class)
        public static GlobArrayField students;

        @GQLQueryParam_(EntityQuery.class)
        @Target(GQLProfessor.class)
        public static GlobField professor;

        @GQLQueryParam_(EntityQuery.class)
        @Target(GQLClass.class)
        @FieldNameAnnotation("class")
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
        @FieldNameAnnotation("class")
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

        @IsJsonContentAnnotation
        public static StringField variables;

        static {
            GlobTypeLoaderFactory.create(GraphQlRequest.class).load();
        }
    }

}
