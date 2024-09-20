package org.globsframework.sample.rest;

import org.apache.http.impl.nio.bootstrap.HttpServer;
import org.apache.http.impl.nio.bootstrap.ServerBootstrap;
import org.globsframework.commandline.ParseCommandLine;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeLoaderFactory;
import org.globsframework.core.metamodel.annotations.AutoIncrement;
import org.globsframework.core.metamodel.annotations.KeyField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.streams.accessors.IntegerAccessor;
import org.globsframework.core.utils.collections.Pair;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.sql.*;
import org.globsframework.sql.annotations.typed.TargetTypeNameAnnotation;
import org.globsframework.sql.constraints.Constraints;
import org.globsframework.sql.drivers.jdbc.JdbcSqlService;

import java.util.concurrent.CompletableFuture;

/*
Expose api route /student en post
Expose un route /api/openapi
create a table
insert in the table and return the newly student.

 */

public class Example1 {

    public static void main(String[] args) {
        Glob argument = ParseCommandLine.parse(ArgumentType.TYPE, args);
        SqlService sqlService = new JdbcSqlService(argument.getNotEmpty(ArgumentType.dbUrl),
                argument.getNotEmpty(ArgumentType.user),
                argument.get(ArgumentType.password));

        {
            SqlConnection db = sqlService.getDb();
            db.createTable(StudentType.TYPE);
            db.commitAndClose();
        }

        final HttpServerRegister httpServerRegister = new HttpServerRegister("EstablishmentServer/0.1");
        httpServerRegister.register("/student", null)
                .post(StudentType.TYPE, null, (body, url, queryParameters) -> {

                    // insert ths student in db with a direct mapping.
                    SqlConnection db = sqlService.getDb();
                    CreateBuilder createBuilder = db.getCreateBuilder(StudentType.TYPE);

                    body.getOptNotEmpty(StudentType.firstName).ifPresent(v -> createBuilder.set(StudentType.firstName, v));
                    body.getOptNotEmpty(StudentType.lastName).ifPresent(v -> createBuilder.set(StudentType.lastName, v));

                    IntegerAccessor keyGeneratedAccessor = createBuilder.getKeyGeneratedAccessor(StudentType.id);
                    int id;
                    try (SqlRequest insertRequest = createBuilder.getRequest()) {
                        insertRequest.run();
                        id = keyGeneratedAccessor.getInteger();
                    }
                    db.commit();

                    // query the db with the created student
                    Glob createdData;
                    try (SelectQuery query = db.getQueryBuilder(StudentType.TYPE, Constraints.equal(StudentType.id, id))
                            .selectAll()
                            .getQuery()) {
                        createdData = query.executeUnique();
                    } finally {
                        db.commitAndClose();
                    }
                    return CompletableFuture.completedFuture(createdData);
                })
                .declareReturnType(StudentType.TYPE);
        httpServerRegister.registerOpenApi();
        Pair<HttpServer, Integer> httpServerIntegerPair =
                httpServerRegister.startAndWaitForStartup(
                        ServerBootstrap.bootstrap()
                                .setListenerPort(argument.get(ArgumentType.port, 3000)));
        System.out.println("Listen on port: " + httpServerIntegerPair.getSecond());
    }

    public static class StudentType {
        @TargetTypeNameAnnotation("students")
        public static GlobType TYPE;

        @KeyField
        @AutoIncrement
        public static IntegerField id;

        public static StringField firstName;

        public static StringField lastName;

        static {
            GlobTypeLoaderFactory.create(StudentType.class).load();
        }
    }
/*
    public static class Argument {

        public String dbUrl;

        public String user;

        public String password;

        public int port;
    }
    Argument arguments = new Argument();
    ParseResult parseResult = new CommandLine(arguments).parseArgs(args);
*/
    // CommandLine interpreter that uses reflection to initialize an annotated user object
    // with values obtained from the command line arguments.

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

//    class MangedObject {
//        GlobType type;
//        Map<Field, Object> data;
//    }
//
//    void jsonSerialize(StringBuilder buf, MangedObject mangedObject) {
//        buf.append("{\n");
//        Field[] fields = mangedObject.type.getFields();
//        for (Field field : fields) {
//            buf.append("\"").append(field.getName()).append("\":").append(mangedObject.data.get(field)).append(",");
//        }
//    }
//
//    void generateJsonSerialiser(StringBuffer buf, Point mo){
//        buf.append("{");
//        buf.append("‘x’:");
//        buf.append(mo.x);
//        buf.append(",");
//    }
//
//
//        void GenerateJson(GlobType moType, StringBuffer buf)	{
//        buf.append("generateJsonSerialiser(StringBuffer buf,");
//        buf.append(moType.getName()).append("){\n");
//        for (Field field : moType.getFields()) {
//            buf.append("\"").append(field.getName()).append("\"");
//            buf.append("mo.").append(field.getName());
//        }
//    }
//
//    class PointType {
//        public static GlobType TYPE;
//
//        public static IntegerField x;
//
//        public static IntegerField y;
//    }
//
//    Point shift(Point point, int x, int y) {
//        return new Point(point.x + x, point.y + y);
//    }
//
//    Glob shift(Glob mo, int x, int y) {
//        return PointType.TYPE.instantiate()
//                .set(PointType.x, mo.get(PointType.x))
//                .set(PointType.y, mo.get(PointType.y));
//    }


}
