/*
 * (c)2025 CAE Tech Limited
 */
package tech.cae.bhombuilder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.codemodel.JCodeModel;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.txt.UniversalEncodingDetector;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJsonProvider;
import org.jsonschema2pojo.ContentResolver;
import org.jsonschema2pojo.DefaultGenerationConfig;
import org.jsonschema2pojo.GenerationConfig;
import org.jsonschema2pojo.Jackson2Annotator;
import org.jsonschema2pojo.RuleLogger;
import org.jsonschema2pojo.Schema;
import org.jsonschema2pojo.SchemaGenerator;
import org.jsonschema2pojo.SchemaMapper;
import org.jsonschema2pojo.SchemaStore;
import org.jsonschema2pojo.rules.RuleFactory;
import picocli.CommandLine;

/**
 * Utility for converting BHoM schema from Github repo to Java code
 *
 * @author Peter Harman peter.harman@cae.tech
 */
public class BHoMBuilder implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(BHoMBuilder.class.getName());

    @CommandLine.Option(names = {"-pat"}, description = "Github Personal Access Token (PAT)", required = true)
    String pat;

    @CommandLine.Option(names = {"-owner"}, description = "Github Org of Schema", required = false)
    String schemaOwner = "BHoM";

    @CommandLine.Option(names = {"-repo"}, description = "Github Repo of Schema", required = false)
    String schemaRepo = "BHoM_JSONSchema";

    @CommandLine.Option(names = {"-branch"}, description = "Version", required = false)
    String schemaBranch = "develop";

    @CommandLine.Option(names = {"-p"}, description = "Base package", required = false)
    String basePackage = "xyz.bhom";

    @CommandLine.Option(names = {"-dir"}, description = "Target directory", required = true)
    File dir;

    public static void main(String[] args) {
        BHoMBuilder generator = new BHoMBuilder();
        new CommandLine(generator).execute(args);
    }
    private final ObjectMapper objectMapper;
    private final SchemaMapper mapper;
    private final Client client;

    public BHoMBuilder() {
        this.objectMapper = new ObjectMapper();
        GenerationConfig config = new DefaultGenerationConfig() {
            @Override
            public boolean isGenerateBuilders() { // set config option by overriding method
                return true;
            }
        };
        this.mapper = new SchemaMapper(
                new RuleFactory(config,
                        new Jackson2Annotator(config),
                        new CaptureUriSchemaStore( //                                new UTF8ContentResolver(objectMapper),
                        //                                new LoggingRuleLogger(LOG)
                        )),
                new SchemaGenerator());
        ClientConfig clientConfig = new ClientConfig();
        JacksonJsonProvider provider = new JacksonJsonProvider();
        provider.setMapper(this.objectMapper);
        clientConfig.register(provider);
        clientConfig.property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true);
        this.client = ClientBuilder.newClient(clientConfig);
    }

    @Override
    public Integer call() throws Exception {
        this.dir.mkdirs();
        for (GithubReference reference : getContents()) {
            generate(reference);
        }
        fixCharset(this.dir);
        return 0;
    }

    void generate(GithubReference reference) throws IOException {
        if ("dir".equals(reference.type)) {
            for (GithubReference subreference : getContents(new URL(reference.url))) {
                generate(subreference);
            }
        }
        if (reference.download_url != null && reference.download_url.endsWith(".json")) {
            URL schemaURL = new URL(reference.download_url);
            generate(reference, schemaURL);
        }
    }

    @SuppressWarnings("UseSpecificCatch")
    void generate(GithubReference reference, URL schemaURL) throws IOException {
        System.out.println("Schema at " + schemaURL.toString());
        System.out.println(getPackage(reference) + "." + getClassName(reference));
        try {
            JCodeModel codeModel = new JCodeModel();
            mapper.generate(codeModel, "ClassName", getPackage(reference), schemaURL);
            codeModel.build(dir);
        } catch (Exception ex) {
            System.out.println("Failed to generate for " + schemaURL);
        }
    }

    URL getContentsURL() throws MalformedURLException {
        return getContentsURL(Arrays.asList());
    }

    URL getContentsURL(List<String> path) throws MalformedURLException {
        return new URL("https://api.github.com/repos/"
                + schemaOwner + "/"
                + schemaRepo + "/contents"
                + (path.isEmpty() ? "" : "/" + path.stream().reduce("", (a, b) -> a + "/" + b)));
    }

    String getClassName(GithubReference reference) {
        String[] paths = reference.path.split("/");
        String[] nameExtension = paths[paths.length - 1].split("\\.");
        return nameExtension[0];
    }

    String getPackage(GithubReference reference) {
        String[] paths = reference.path.split("/");
        return Stream.of(paths)
                .limit(paths.length - 1)
                .map(path -> path.toLowerCase())
                .map(path -> path.endsWith("_om") ? path.substring(0, path.length() - 3) : path)
                .reduce(basePackage, (a, b) -> a + "." + b);
    }

    List<GithubReference> getContents() throws IOException {
        return getContents(getContentsURL());
    }

    List<GithubReference> getContents(URL url) throws IOException {
        try {
            return objectMapper.treeToValue(get(url), this.objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, GithubReference.class));
        } catch (URISyntaxException | WebApplicationException ex) {
            throw new IOException(ex);
        }
    }

    JsonNode get(URL url) throws URISyntaxException, WebApplicationException {
        return client.target(url.toURI())
                .request(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + pat)
                .get(JsonNode.class);
    }

    public void fixCharset(File f) throws IOException {
        if (f.isDirectory()) {
            for (File child : f.listFiles()) {
                fixCharset(child);
            }
        } else {
            System.out.println("Checking charset of " + f);
            EncodingDetector encodingDetector = new UniversalEncodingDetector();
            Charset charset = null;
            try (InputStream fis = Files.newInputStream(f.toPath()); BufferedInputStream bis = new BufferedInputStream(fis)) {
                charset = encodingDetector.detect(bis, new Metadata());
            }
            if (!charset.equals(Charset.forName("UTF-8"))) {
                System.out.println("Rewriting " + f + " as UTF-8");
                String content = Files.readString(f.toPath(), Charset.forName("ISO-8859-1"));
                Files.writeString(f.toPath(), content);
            }
        }
    }

    static class GithubReference {

        @JsonProperty
        private String name;
        @JsonProperty
        private String path;
        @JsonProperty
        private String sha;
        @JsonProperty
        private Integer size;
        @JsonProperty
        private String url;
        @JsonProperty
        private String html_url;
        @JsonProperty
        private String git_url;
        @JsonProperty
        private String download_url;
        @JsonProperty
        private String type;
        @JsonProperty("_links")
        private GithubReferenceLinks links;

    }

    static class GithubReferenceLinks {

        @JsonProperty
        private String self;
        @JsonProperty
        private String git;
        @JsonProperty
        private String html;

    }

    static class CaptureUriSchemaStore extends SchemaStore {

        public CaptureUriSchemaStore() {
        }

        public CaptureUriSchemaStore(ContentResolver contentResolver, RuleLogger logger) {
            super(contentResolver, logger);
        }

        @Override
        public synchronized Schema create(URI id, String refFragmentPathDelimiters) {
            System.out.println("Referenced schema at " + id);
            return super.create(id, refFragmentPathDelimiters);
        }

    }

    static class UTF8ContentResolver extends ContentResolver {

        private final ObjectMapper mapper;

        public UTF8ContentResolver(ObjectMapper mapper) {
            super(mapper.getFactory());
            this.mapper = mapper;
        }

        @Override
        public JsonNode resolve(URI uri) {
            try {
                return mapper.readTree(mapper.writeValueAsString(super.resolve(uri)));
            } catch (JsonProcessingException ex) {
                Logger.getLogger(BHoMBuilder.class.getName()).log(Level.SEVERE, null, ex);
                return super.resolve(uri);
            }
        }

    }

    static class LoggingRuleLogger implements RuleLogger {

        private final Logger log;

        public LoggingRuleLogger(Logger log) {
            this.log = log;
        }

        @Override
        public void debug(String string) {
            log.log(Level.FINE, string);
        }

        @Override
        public void error(String string) {
            log.log(Level.SEVERE, string);
        }

        @Override
        public void error(String string, Throwable thrwbl) {
            log.log(Level.SEVERE, string, thrwbl);
        }

        @Override
        public void info(String string) {
            log.log(Level.INFO, string);
        }

        @Override
        public boolean isDebugEnabled() {
            return log.isLoggable(Level.FINE);
        }

        @Override
        public boolean isErrorEnabled() {
            return log.isLoggable(Level.SEVERE);
        }

        @Override
        public boolean isInfoEnabled() {
            return log.isLoggable(Level.INFO);
        }

        @Override
        public boolean isTraceEnabled() {
            return log.isLoggable(Level.FINER);
        }

        @Override
        public boolean isWarnEnabled() {
            return log.isLoggable(Level.WARNING);
        }

        @Override
        public void trace(String string) {
            log.log(Level.FINER, string);
        }

        @Override
        public void warn(String string, Throwable thrwbl) {
            log.log(Level.WARNING, string, thrwbl);
        }

        @Override
        public void warn(String string) {
            log.log(Level.WARNING, string);
        }

    }

}
