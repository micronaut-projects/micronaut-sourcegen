package io.micronaut.sourcegen.generator.visitors

class MavenPluginGenerationSpec extends AbstractGenerationSpec {

    void "test simple maven plugin generation"() {
        when:
        var files = generateSources("test.Wolf", """
        package test;
        import io.micronaut.sourcegen.annotations.*;

        @GenerateMavenMojo(
            micronautPlugin = false,
            source = "test.Wolf"
        )
        @PluginTask
        public record Wolf(
                @PluginTaskParameter(required = true)
                String slogan,
                @PluginTaskParameter(defaultValue = "1")
                Integer age
        ) {

            @PluginTaskExecutable
            public void awooo() {
            }

        }
        """)

        then:
        var mojoContent = stripImports(files.get("test.WolfMojo").getCharContent(false))
        mojoContent == """/**
 * Wolf Maven Mojo.
 */
public abstract class WolfMojo extends AbstractMojo {
  /**
   * Configurable slogan parameter.
   */
  @Parameter(
      required = true
  )
  protected String slogan;

  /**
   * Configurable age parameter.
   */
  @Parameter(
      defaultValue = "1"
  )
  protected Integer age;

  /**
   * Determines if this mojo must be executed.
   * @return true if the mojo is enabled
   */
  protected abstract boolean isEnabled();

  /**
   * Main execution of Wolf Mojo.
   */
  public void execute() {
    if (!this.isEnabled()) {
      this.getLog().debug("WolfMojo is disabled");
      return;
    }
    Wolf task = new test.Wolf(this.slogan, this.age);
    task.awooo();
  }
}"""
    }

}
