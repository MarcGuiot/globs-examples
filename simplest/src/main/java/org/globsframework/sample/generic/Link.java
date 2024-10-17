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

import java.lang.annotation.Annotation;

public class Link {
    public static GlobType TYPE;

    public static StringField fromField;

    public static StringField toField;

    @InitUniqueKey
    public static Key KEY;


    static {
        GlobTypeLoader loader = GlobTypeLoaderFactory.create(Link.class);
        loader.register(GlobCreateFromAnnotation.class, Link::create);
        loader.load();
    }

    private static Glob create(Annotation annotation) {
        return TYPE.instantiate()
                .set(fromField, ((Link_) annotation).from())
                .set(toField, ((Link_) annotation).to());
    }
}
