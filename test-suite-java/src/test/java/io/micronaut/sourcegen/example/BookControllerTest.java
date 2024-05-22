package io.micronaut.sourcegen.example;

import io.micronaut.context.annotation.Property;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Property(name = "datasources.default.password", value = "")
@Property(name = "datasources.default.dialect", value = "H2")
@Property(name = "datasources.default.schema-generate", value = "CREATE_DROP")
@Property(name = "datasources.default.url", value = "jdbc:h2:mem:devDb;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE")
@Property(name = "datasources.default.username", value = "sa")
@Property(name = "datasources.default.driver-class-name", value = "org.h2.Driver")
@MicronautTest
class BookControllerTest {


    @Test
    void crud(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        List<Book> books = assertDoesNotThrow(() -> client.retrieve(HttpRequest.GET("/books"), Argument.listOf(Book.class)));
        assertTrue(books.isEmpty());
        String title = "More Java 17";
        HttpResponse<?> response = assertDoesNotThrow(() -> client.exchange(HttpRequest.POST("/books", Map.of("title", title))));
        assertEquals(HttpStatus.CREATED, response.status());

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () -> client.retrieve(HttpRequest.GET("/libros")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());

        books = assertDoesNotThrow(() -> client.retrieve(HttpRequest.GET("/libros").basicAuth("sherlock", "elementary"), Argument.listOf(Book.class)));
        assertFalse(books.isEmpty());

        books = assertDoesNotThrow(() -> client.retrieve(HttpRequest.GET("/books"), Argument.listOf(Book.class)));
        assertFalse(books.isEmpty());
        Book book = books.get(0);
        assertNotNull(book);
        assertNotNull(book.id());
        assertEquals(title, book.title());
        URI location = UriBuilder.of("/books").path(book.id().toString()).build();
        assertNotNull(location);

        book = assertDoesNotThrow(() -> client.retrieve(HttpRequest.GET(location), Book.class));
        String bookId = book.id().toString();
        assertNotNull(book);
        assertNotNull(book.id());
        assertEquals(title, book.title());

        ex = assertThrows(HttpClientResponseException.class, () -> client.retrieve(HttpRequest.GET("/books/999"), Book.class));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());


        String updatedTitle = "Netty in Action";
        response = assertDoesNotThrow(() -> client.exchange(HttpRequest.PUT("/books", Map.of("id", bookId, "title", updatedTitle))));
        assertEquals(HttpStatus.NO_CONTENT, response.status());

        book = assertDoesNotThrow(() -> client.retrieve(HttpRequest.GET(location), Book.class));
        assertNotNull(book);
        assertNotNull(book.id());
        assertEquals(updatedTitle, book.title());

        response = assertDoesNotThrow(() -> client.exchange(HttpRequest.DELETE(UriBuilder.of("/books").path(bookId).build())));
        assertEquals(HttpStatus.NO_CONTENT, response.status());

    }
}
