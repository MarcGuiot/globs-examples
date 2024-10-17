package org.globsframework.sample.generic;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeLoader;
import org.globsframework.core.metamodel.GlobTypeLoaderFactory;
import org.globsframework.core.metamodel.annotations.GlobCreateFromAnnotation;
import org.globsframework.core.metamodel.annotations.InitUniqueKey;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.Key;
import org.globsframework.graphql.model.GQLQueryParam_;
import org.globsframework.graphql.model.GraphqlEnum_;

import java.lang.annotation.Annotation;

public class DbTarget {
    public static GlobType TYPE;

    public static StringField dbResource;

    @InitUniqueKey
    public static Key KEY;

    static {
        GlobTypeLoader loader = GlobTypeLoaderFactory.create(DbTarget.class);

        loader.register(GlobCreateFromAnnotation.class, DbTarget::create);
        loader.load();
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
