package org.globsframework.sample.generic;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeLoader;
import org.globsframework.core.metamodel.GlobTypeLoaderFactory;
import org.globsframework.core.metamodel.annotations.GlobCreateFromAnnotation;
import org.globsframework.core.metamodel.annotations.InitUniqueGlob;
import org.globsframework.core.metamodel.annotations.InitUniqueKey;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.Key;
import org.globsframework.graphql.model.GQLQueryParam_;

import java.lang.annotation.Annotation;

public class Searchable {
    public static GlobType TYPE;

    @InitUniqueKey
    public static Key KEY;

    @InitUniqueGlob
    public static Glob INSTANCE;

    static {
        GlobTypeLoader loader = GlobTypeLoaderFactory.create(Searchable.class);
        loader.register(GlobCreateFromAnnotation.class, Searchable::create);
        loader.load();
    }

    private static Glob create(Annotation annotation) {
        return INSTANCE;
    }
}
