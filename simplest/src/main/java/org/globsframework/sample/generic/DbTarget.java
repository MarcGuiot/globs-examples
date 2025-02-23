package org.globsframework.sample.generic;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.GlobCreateFromAnnotation;
import org.globsframework.core.metamodel.annotations.InitUniqueKey;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.Key;
import org.globsframework.core.model.KeyBuilder;

import java.lang.annotation.Annotation;

public class DbTarget {
    public static final GlobType TYPE;

    public static final StringField dbResource;

    @InitUniqueKey
    public static final Key KEY;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("DbTarget");
        TYPE = typeBuilder.unCompleteType();
        dbResource = typeBuilder.declareStringField("dbResource");
        typeBuilder.complete();
        KEY = KeyBuilder.newEmptyKey(TYPE);
        typeBuilder.register(GlobCreateFromAnnotation.class, DbTarget::create);

//        GlobTypeLoader loader = GlobTypeLoaderFactory.create(DbTarget.class);
//        loader.register(GlobCreateFromAnnotation.class, DbTarget::create);
//        loader.load();
    }

    private static Glob create(Annotation annotation) {
        try {
            return TYPE.instantiate()
                    .set(dbResource, ((GlobType) ((DbTarget_) annotation).value().getField("TYPE").get(null)).getName());
        } catch (Exception e) {
            throw new RuntimeException("Fail to extract TYPE");
        }
    }
}
