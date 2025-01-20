package io.micronaut.sourcegen.generator.visitors

class GradlePluginGenerationSpec extends AbstractGenerationSpec {

    void "test simple gradle plugin generation"() {
        when:
        var files = generateSources("test.Wolf", """
        package test;
        import io.micronaut.sourcegen.annotations.*;

        @GenerateGradlePlugin(
            micronautPlugin = false,
            tasks = @GenerateGradlePlugin.GenerateGradleTask(
                source = "test.Wolf"
            )
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
        var taskContent = stripImports(files.get("test.WolfTask").getCharContent(false))
        taskContent == """/**
 * Wolf Gradle task.
 */
@CacheableTask
public abstract class WolfTask extends DefaultTask {
  /**
   * Configurable slogan parameter.
   */
  @Input
  public abstract Property<String> getSlogan();

  /**
   * Configurable age parameter.
   */
  @Input
  @Optional
  public abstract Property<Integer> getAge();

  @Classpath
  public abstract ConfigurableFileCollection getClasspath();

  @Inject
  public abstract WorkerExecutor getWorkerExecutor();

  /**
   * Main execution of Wolf task.
   */
  @TaskAction
  public void execute() {
    this.getWorkerExecutor().classLoaderIsolation(new WolfClasspathConfigurator(this)).submit(WolfWorkAction.class, new WolfWorkActionParameterConfigurator(this));
  }

  public abstract static class WolfWorkAction implements WorkAction<WolfWorkActionParameters> {
    public void execute() {
      WolfWorkActionParameters parameters = this.getParameters();
      Wolf task = new test.Wolf(parameters.getSlogan().get(), parameters.getAge().get());
      task.awooo();
    }
  }

  public interface WolfWorkActionParameters extends WorkParameters {
    Property<String> getSlogan();

    Property<Integer> getAge();
  }

  public static class WolfWorkActionParameterConfigurator implements Action<WolfWorkActionParameters> {
    WolfTask task;

    public WolfWorkActionParameterConfigurator(WolfTask task) {
      this.task = task;
    }

    public void execute(WolfWorkActionParameters params) {
      params.getSlogan().set(this.task.getSlogan());
      params.getAge().set(this.task.getAge().orElse(1));
    }
  }

  public static class WolfClasspathConfigurator implements Action<ClassLoaderWorkerSpec> {
    WolfTask task;

    public WolfClasspathConfigurator(WolfTask task) {
      this.task = task;
    }

    public void execute(ClassLoaderWorkerSpec spec) {
      spec.getClasspath().from(this.task.getClasspath());
    }
  }
}"""

        var specContent = stripImports(files.get("test.WolfSpec").getCharContent(false))
        specContent == """/**
 * Specification that is used for configuring Wolf task.
 * Wolf Gradle task.
 */
public interface WolfSpec {
  /**
   * Configurable slogan parameter.
   */
  Property<String> getSlogan();

  /**
   * Configurable age parameter.
   */
  Property<Integer> getAge();
}"""

        var extensionContent = stripImports(files.get("test.WolfExtension").getCharContent(false))
        extensionContent == """/**
 * Configures the Wolf execution.
 */
public interface WolfExtension {
  /**
   * Create a task for awooo.
   * Main execution of Wolf task.
   * @param name The unique identifier used to derive task names
   * @param spec The configurable specification
   */
  void awooo(String name, Action<WolfSpec> action);
}"""

        var defaultExtensionContent = stripImports(files.get("test.DefaultWolfExtension").getCharContent(false))
        defaultExtensionContent == """public abstract class DefaultWolfExtension implements WolfExtension {
  protected final Set<String> names = new java.util.HashSet();

  protected final Project project;

  protected final Configuration classpath;

  @Inject
  public DefaultWolfExtension(Project project, Configuration classpath) {
    this.project = project;
    this.classpath = classpath;
  }

  public void awooo(String name, Action<WolfSpec> action) {
    if (!this.names.add(name)) {
      throw new org.gradle.api.GradleException(String.format("An awooo definition with name '%s' was already created", name));
    }
    WolfSpec spec = this.project.getObjects().newInstance(WolfSpec.class);
    this.configureSpec(spec);
    action.execute(spec);
    TaskProvider<? extends WolfTask> task = this.createWolfTask(name, new WolfTaskConfigurator(spec, this.classpath));
  }

  TaskProvider<? extends WolfTask> createWolfTask(String name, Action<WolfTask> configurator) {
    return this.project.getTasks().register(name, WolfTask.class, configurator);
  }

  protected void configureSpec(WolfSpec spec) {
    spec.getAge().convention(1);
  }

  protected static class WolfTaskConfigurator implements Action<WolfTask> {
    WolfSpec spec;

    Configuration classpath;

    WolfTaskConfigurator(WolfSpec spec, Configuration classpath) {
      this.spec = spec;
      this.classpath = classpath;
    }

    public void execute(WolfTask arg1) {
      arg1.getClasspath().from(this.classpath);
      arg1.setDescription("Configure the awooo");
      arg1.getSlogan().convention(this.spec.getSlogan());
      arg1.getAge().convention(this.spec.getAge());
    }
  }
}"""

        var pluginContent = stripImports(files.get("test.WolfPlugin").getCharContent(false))
        pluginContent == """public class WolfPlugin implements Plugin<Project> {
  protected WolfExtension createExtension(Project project, Configuration classpath) {
    return project.getExtensions().create(WolfExtension.class, "Wolf", DefaultWolfExtension.class, project, classpath);
  }

  public void apply(Project project) {
    Configuration dependencies = project.getConfigurations().create("WolfConfiguration");
    dependencies.setCanBeResolved(false);
    dependencies.setCanBeConsumed(false);
    dependencies.setDescription("The Wolf worker dependencies");
    Configuration classpath = project.getConfigurations().create("WolfClasspath");
    classpath.setCanBeResolved(true);
    classpath.setCanBeConsumed(false);
    classpath.setDescription("The Wolf worker classpath");
    classpath.extendsFrom(dependencies);
    this.createExtension(project, classpath);
  }
}"""
    }

}
