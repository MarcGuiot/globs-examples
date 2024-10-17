package org.globsframework.sample.generic;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeLoader;
import org.globsframework.core.metamodel.GlobTypeLoaderFactory;
import org.globsframework.core.metamodel.annotations.GlobCreateFromAnnotation;
import org.globsframework.core.metamodel.annotations.InitUniqueGlob;
import org.globsframework.core.metamodel.annotations.InitUniqueKey;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.Key;

import java.lang.annotation.Annotation;

public class IsConnection {
    public static GlobType TYPE;

    @InitUniqueKey
    public static Key KEY;

    @InitUniqueGlob
    public static Glob INSTANCE;

    static {
        GlobTypeLoader loader = GlobTypeLoaderFactory.create(IsConnection.class);
        loader.register(GlobCreateFromAnnotation.class, IsConnection::create);
        loader.load();
    }

    private static Glob create(Annotation annotation) {
        return INSTANCE;
    }
}
