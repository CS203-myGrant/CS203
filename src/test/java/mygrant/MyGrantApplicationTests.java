package mygrant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MyGrantApplicationTests {

    @Test
    void applicationClassCanBeCreated() {
        assertThat(new MyGrantApplication()).isNotNull();
    }
}
