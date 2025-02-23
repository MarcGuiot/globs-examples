package org.globsframework.sample.generic;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.GlobCreateFromAnnotation;
import org.globsframework.core.metamodel.annotations.InitUniqueGlob;
import org.globsframework.core.metamodel.annotations.InitUniqueKey;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.Key;
import org.globsframework.core.model.KeyBuilder;

import java.lang.annotation.Annotation;

public class IsConnection {
    public static final GlobType TYPE;

    @InitUniqueKey
    public static final Key UNIQUE_KEY;

    @InitUniqueGlob
    public static final Glob UNIQUE_INSTANCE;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("IsConnection");
        TYPE = typeBuilder.unCompleteType();
        typeBuilder.complete();
        UNIQUE_KEY = KeyBuilder.newEmptyKey(TYPE);
        UNIQUE_INSTANCE = TYPE.instantiate();
        typeBuilder.register(GlobCreateFromAnnotation.class, IsConnection::create);

//        GlobTypeLoader loader = GlobTypeLoaderFactory.create(IsConnection.class);
//        loader.register(GlobCreateFromAnnotation.class, IsConnection::create);
//        loader.load();
    }

    private static Glob create(Annotation annotation) {
        return UNIQUE_INSTANCE;
    }
}
