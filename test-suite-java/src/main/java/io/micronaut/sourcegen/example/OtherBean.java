package io.micronaut.sourcegen.example;

import io.micronaut.context.annotation.Executable;
import jakarta.inject.Singleton;
import java.net.http.HttpResponse;
import java.util.List;

@Singleton
public class OtherBean {
    @Executable
    HttpResponse<List<MyBean1>> someMethod() {
        return null;
    }
}
