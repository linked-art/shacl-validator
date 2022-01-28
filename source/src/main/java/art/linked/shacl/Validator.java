/**
* Validator - the linked art validator, aka "lav"
* A tool for validating JSON-LD data against the linked art ontology by checking the data against
* SHACL shapes which will eventually enjoy 100% coverage over all entities and properties used by
* linked art.  
* 
* See the command line help by running lav -h from the bin/ folder
* 
* @author      Dave Beaudet
* @version     0.1
* @since       0.1
*/
package art.linked.shacl;

import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.http.entity.ContentType;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.topbraid.shacl.validation.ValidationEngine;
import org.topbraid.shacl.validation.ValidationEngineConfiguration;
import org.topbraid.shacl.validation.ValidationReport;
import org.topbraid.shacl.validation.ValidationResult;
import org.topbraid.shacl.validation.ValidationUtil;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IParameterPreprocessor;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import org.apache.jena.shacl.validation.Severity;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ShutdownHandler;

@Command(
        name = "lav", 
        mixinStandardHelpOptions = true, 
        sortOptions = false,
        showDefaultValues = true,
        version = "0.1",
        description = "The Linked Art Validator (lav) validates json-ld input against the Linked Art version 1.0 spec release candidate. Returns exit code of 0 if all data validates."
        )
public class Validator implements Callable<Integer>{

    private static final Logger log = LoggerFactory.getLogger(Validator.class);

    static final String SHACLINFO = Severity.Info.level().toString();
    static final String SHACLWARNING = Severity.Warning.level().toString();
    static final String SHACLVIOLATION = Severity.Violation.level().toString();
    
    @Spec CommandSpec spec;
    
    static String INSTALLDIR;
    static {
        try { 
            INSTALLDIR = new File(Validator.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            if ( INSTALLDIR.contains("target") )
                INSTALLDIR = new File(new File(INSTALLDIR).getParent()).getParent();
            else
                INSTALLDIR = new File(INSTALLDIR).getParent();
            INSTALLDIR += File.separator;
        }
        catch (Exception e) {
           log.error("Could not determine path to the running JAR file");
           halt();
        }
    }
    
    static class PathOptionsProcessor implements IParameterPreprocessor {
        public boolean preprocess(Stack<String> args,
                                  CommandSpec commandSpec,
                                  ArgSpec argSpec,
                                  Map<String, Object> info) {
            String installDir = pathTo(INSTALLDIR).toString();
            for ( int i = 0; i < args.size(); i++) {
                String s = args.get(i).replace("<install dir>", installDir);
                s = pathTo(s).toString();
                args.set(i,s);
            }
            return false; // picocli's internal parsing is resumed for this option
        }
    }

    // INPUT OPTIONS
    @Option(
            order = 10,
            names = {"-i", "--input"}, 
            defaultValue = "-",
            description = "Input file, folder (walked), or URL containing JSON-LD data to validate"
            )
    static String dataSource; // = "-";

    @Option (
            order = 11,
            names = {"-b", "--baseURLPattern"},
            defaultValue = ".*linked\\.art.*",
            description = "Regex used to match URIs to determine if they are considered part of the data set being validated or external to it"
            )
    static String baseURLPattern; // = ".*linked\\.art.*";
    
    // VALIDATION OPTIONS
    @Option (
            names = {"-j", "--jsonSchemaValidation"},
            order = 12,
            defaultValue = "false",
            description = "Validate input against Linked Art JSON Schema in addition to SHACL validation"
            )
    static boolean validateAgainstJSONSchema;

    @Option (
            names = {"-g", "--printDataGraph"},
            arity = "1",
            order = 13,
            defaultValue = "true",
            description = "When input data has validation errors or recommendations, also print / log an RDF version of the parsed input"
            )
    static Boolean outputDataGraph;

    @Option (
            names = {"-q", "--quiet"},
            // arity = "0",
            order = 14,
            defaultValue = "false",
            description = "Suppress all output and rely on exit values only"
            )
    static boolean quiet;
    


    // SERVER OPTIONS
    @Option(
            names = {"-s", "--start"}, 
            order = 20,
            defaultValue = "false",
            description = "Start the validation service and wait for connections"
            )
    static boolean startServer; // = false;

    @Option(
            names = {"-k", "--stop"}, 
            order = 21,
            defaultValue = "false",
            description = "Stop the validation service by sending a shutdown command with the password"
            )
    static boolean stopServer; // = false;

    @Option(
            names = {"-p", "--password"}, 
            order = 22,
            defaultValue = "Mijn hovercraft zit vol palingen",
            description = "Password required to shutdown the validation service"
            )
    static String serverSecret; // = "quit";
    

    @Option(
            names = {"-u", "--url"}, 
            order = 23,
            description = "Validation service URL to start or access as a client\ndefault: http://localhost:52525"
            )
    static String serviceURL = null;
    static String defaultServiceURL = "http://localhost:52525";

    // SOURCE MODELS, BASE DATA, AND SHAPES
    @Option(
            names = {"--shapes"}, 
            order = 30,
            preprocessor = PathOptionsProcessor.class,
            description = "shapes file or folder containing the SHACL shapes (TTL) to be used for validation",
            defaultValue = "<install dir>/shapes/"
            )
    static String shapesFileOrFolder; // = pathTo(INSTALLDIR+"shapes").toString();

    @Option(
            names = {"--terms"}, 
            order = 31,
            preprocessor = PathOptionsProcessor.class,
            description = "folder containing Getty AAT terms used by the validator",
            defaultValue = "<install dir>/terms/"
            )
    static String termsFileOrFolder = pathTo(INSTALLDIR,"terms");

    @Option(
            names = {"--model"}, 
            order = 32,
            preprocessor = PathOptionsProcessor.class,
            description = "TTL file containing the linked art model",
            defaultValue = "<install dir>/ontology/linkedArtReduced.model.ttl"
            )
    static String ontologyModelFile = pathTo(INSTALLDIR,"ontology/linkedArtReduced.model.ttl").toString();
    
    

    static Map<String,Schema> jsonSchemas = null;
    static Map<String,String> entityAliases = new HashMap<>();
    static {
        entityAliases.put("activity",             "provenance");
        entityAliases.put("type",                 "concept");
        entityAliases.put("digitalobject",        "digital");
        entityAliases.put("group",                "group");
        entityAliases.put("humanmadeobject",      "object");
        entityAliases.put("person",               "person");
        entityAliases.put("place",                "place");
        entityAliases.put("set",                  "set");
        entityAliases.put("linguisticobject",     "text");
        entityAliases.put("visualitem",           "image");
    }
    static Map<String,String> propertySubs = new HashMap<>();

    static Pattern constraintIDPattern = Pattern.compile(".*([vw]\\d+):.*");
    
    static OntModel ontologyModel = null;
    static OntModel shapesModel = null;
    
    static void info(String s) {
        if ( !quiet )
            log.info(s);
    }
    static void info(LAValidationException lve, String s) {
        if ( lve != null )
            lve.info(s);
        info(s);
    }

    static void warn(String s) {
        if ( !quiet )
            log.warn(s);
    }
    static void warn(LAValidationException lve, String s) {
        if ( lve != null )
            lve.warn(s);
        warn(s);
    }

    static void error(String s) {
        if ( !quiet )
            log.error(s);
    }
    static void error(LAValidationException lve, String s) {
        if ( lve != null )
            lve.error(s);
        error(s);
    }

    public static void logValidationMessage(String s, String message, LAValidationException lve) {
        if ( s.equals(SHACLINFO)) 
            info(lve, "Optional: " + message);
        else if ( s.equals(SHACLWARNING)) 
            warn(lve, "Recommendation: " + message);
        else 
            error(lve, "Violation: " + message);
    }

    
    static Schema cacheSchema(String url) throws JSONException, MalformedURLException, IOException {

        return SchemaLoader.builder()
                .schemaJson(new JSONObject ( new JSONTokener ( new URL(url).openStream() ) ))
                .resolutionScope(url) 
                .build().load().build();
    }

    static void validateAgainstJsonSchemas(String jsonld) throws JSONException, IOException {
        
        // cache JSON schemas if needs be
        if (jsonSchemas == null) {
            Path schemaFolder = pathTo(INSTALLDIR+"json-schema");
            jsonSchemas = new HashMap<>();
            for ( String entityName : 
                Arrays.asList("activity","core","concept","digital","event","group","image","object","person","place","provenance","set","text","visual")) {
                File schemaFile = new File(schemaFolder + File.separator + entityName + ".json");
                if ( Files.exists(schemaFile.toPath())) {
                    jsonSchemas.put( entityName, cacheSchema(schemaFile.toURI().toURL().toString()) ); 
                }
            }
        }

        JSONObject jsonData = new JSONObject(new JSONTokener(jsonld) );
        String entityType=null;
        try {
            entityType = jsonData.getString("type");
        } 
        catch ( JSONException j) {
            error("No type assigned to a root object(s) so no schema validation is possible.");
        };
        String entityAlias = null;
        if ( entityType != null ) 
            entityAlias = entityAliases.get(entityType.toLowerCase());
        Schema schema = null;
        if ( entityAlias != null)
            schema = jsonSchemas.get(entityAlias.toLowerCase());
        if ( schema == null ) {
            warn("No JSON Schema file for " + entityType + " (a.k.a. " + entityAlias + ")");
        }
        else {
            info("");
            info("=====================================================================================================");
            info("validating data against JSON Schema: " + entityAlias + ".json");
            schema.validate(jsonData);
            info("=====================================================================================================");
            info("");
        }
    }

    public static void populateModel(Model m, Path p) {
        info("Populating model with " + p.toString());
        m.read(p.toString());
    }

    public static Path pathTo(String p) {
        Path path = Paths.get(p);
        
        // necessary since isAbsolute doesn't consider a path starting with slash to be absolute
        // since it's relative to a drive letter or volume so we need to account for this
        if ( path.startsWith("/") || path.startsWith("\\") )
            return path;
        
        if ( !path.isAbsolute() ) {
            String pathAsString = Paths.get(".").toAbsolutePath().normalize() + File.separator + p;
            path = Paths.get(pathAsString);
        }
        return path;
    }

    public static String pathTo(String p1, String p2) {
        Path path = Paths.get(p1+File.separator+p2);
        if ( !path.isAbsolute() ) {
            String pathAsString = Paths.get(".").toAbsolutePath().normalize() + File.separator + path.toString();
            path = Paths.get(pathAsString);
        }
        return path.toString();
    }


    public static void substituteShapePatternVars(Model shapes, Map<String,String> props, String... predicates) {
        // list all uses of sh:pattern in our shapes
        // and search for sh:messages in which to substitute any dynamic patterns
        for (String predicateURL : predicates) {
            Property p = shapes.getProperty(predicateURL);
            if ( p != null ) {
                for ( Statement s : shapes.listStatements((Resource) null, p, (Resource) null).toList() ) {
                    String pattern = s.getObject().toString();
                    for ( String var : props.keySet()) {
                        String val = props.get(var);
                        String newPattern = pattern.replaceAll("\\$"+var, val);
                        if ( !pattern.equals(newPattern))
                            s.changeObject(newPattern);
                    }
                }
            }
        }

    }

    static void registerNSPrefixes(Model model) {
        // set CRM prefix for convenience with output
        model.setNsPrefix( "crm",   "http://www.cidoc-crm.org/cidoc-crm/"   );
        model.setNsPrefix( "la",    "https://linked.art/ns/terms/"          );
        model.setNsPrefix( "dig",   "http://www.ics.forth.gr/isl/CRMdig/"   );
        model.setNsPrefix( "geo",   "http://www.ics.forth.gr/isl/CRMgeo/"   );
        model.setNsPrefix( "sci",   "http://www.ics.forth.gr/isl/CRMsci/"   );
    }

    static OntModel loadOntology(final OntModel ontology, String ontologyModelFile) throws IOException {
        registerNSPrefixes(ontology);

        // load the linked art models
        populateModel(ontology, pathTo(ontologyModelFile) );

        // load any term hierarchies used in the validation - these might need to be added to the graph as data elements
        // although it's also possible they could reside in the ontology - not sure yet
        Files.walk( pathTo( termsFileOrFolder ) )
        .filter(Files::isRegularFile)
        .filter(  f -> f.getFileName().toString().endsWith(".ttl") )
        .forEach( f -> { 
            populateModel(ontology, f);
        });

        return ontology;
    }

    static OntModel loadShapes(final OntModel shapes, String shapesFileOrFolder) throws IOException {
        registerNSPrefixes(shapes);

        propertySubs.put("baseURLPattern", baseURLPattern);

        // load all of the manually created shapes provided by linked art community members
        Path shapesFolder = pathTo(shapesFileOrFolder);
        // read the shapes from all TTL files found by walking the given path
        Files.walk(shapesFolder)
            .filter(Files::isRegularFile)
            .filter(f -> f.getFileName().toString().endsWith(".ttl"))
            .forEach(g -> { 
                populateModel(shapes, g); 
            });

        // apply variable substitution to the shape patterns and messages to make them more flexible and informative
        substituteShapePatternVars( shapes, propertySubs, "http://www.w3.org/ns/shacl#pattern", "http://www.w3.org/ns/shacl#message" );

        // TODO 
        // consider re-enabling after LA SHACL development is mostly complete to see if there's anything deeper in the 
        // CRM model that we need to pay attention to regarding validation rules that were picked up automatically
        // by TopBraid composer when that tool was used to generate these auto-shapes in the first place
        // populateModel(shapesModel, pathTo("autoshapes/linkedArtReducedShapes.ttl") );

        return shapes;
    }

    static OntModel parseData(final OntModel data, String jsonld) throws IOException {
        registerNSPrefixes(data);
        // validate against JSON schema if enabled
        ObjectMapper mapper = new ObjectMapper();
        try ( JsonParser p = mapper.createParser(jsonld) ) {
            mapper.readTree(p);
            RDFParser.fromString(jsonld).forceLang(Lang.JSONLD11).parse(data.getGraph());
        }
        return data;
    }
    
    static HttpClient getClient() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.setFollowRedirects(true);
        httpClient.start();
        httpClient.getProtocolHandlers().remove(WWWAuthenticationProtocolHandler.NAME);
        return httpClient;
    }
    
    static Severity validateAgainstService(String jsonld) throws Exception {
        
        Severity sev = Severity.Info;
        
        HttpClient httpClient = getClient();
        org.eclipse.jetty.client.api.Request request = httpClient.POST(serviceURL)
                .body(new StringRequestContent("application/json", jsonld))
                .headers( h -> { 
                    h.add("LAVPRINTGRAPH", outputDataGraph.toString());
                } 
        );

        ContentResponse resp = request.send();
        String validationText = resp.getContentAsString();
        if ( resp.getStatus() != HttpStatus.OK_200 || validationText.startsWith("Violation")) {
            error(validationText);
            sev = Severity.Violation;
        }
        else if ( validationText.startsWith("Warning")) {
            warn(validationText);
            sev = Severity.Warning;
        }
        // don't log anything if validation succeeds
        httpClient.stop();
        httpClient.destroy();
        return sev;
    }
    
    // throw runtime exception rather than only System.exit
    static void halt() {
        throw new RuntimeException("Halting due to previous errors.");
    }
    
    
    static Severity validate(InputStream s) {
        
        Severity sev = null;
        try {
            if ( serviceURL != null ) {
                sev = validateAgainstService( new String(s.readAllBytes()) );
            }
            else {
                String jsonld = new String(s.readAllBytes());

                // parse the jsonld into a data model
                OntModel dataModel = parseData(newOntModel(), jsonld);

                if ( validateAgainstJSONSchema )
                    validateAgainstJsonSchemas(jsonld);

                validateData(ontologyModel, shapesModel, dataModel);
            }
        }
        catch (LAValidationException lve) {
            sev = lve.getSeverity();
            if ( lve.getSeverity() == Severity.Violation )
                error("Violation: data did not validate");
            else if ( lve.getSeverity() == Severity.Warning )
                warn("Warning: validated but with recommendations");
            else 
                info("Success: validated ok");
        }
        catch (ValidationException ve) {
            error("There was a problem validating a file against its json schema.");
            for (String m : ve.getAllMessages() )
                error(m);
            halt();
        } 
        catch ( Exception e ) {
            throw new RuntimeException(e);
        }
        return sev;
    }

    static Severity worstFailure;
    static Severity validateByPath(String dataFileOrFolder) throws Exception {
        
        worstFailure = Severity.Info;
        Path dataFolder = pathTo(dataFileOrFolder);
        
        Files.walk(dataFolder)
        .filter(f -> {
            return Files.isRegularFile(f) || Files.isSymbolicLink(f);
        })
        .filter(f -> {
            return f.toString().endsWith(".json");
        })
        .forEach( 
                f -> {
                    try  {
                        info("Validating file: " + f);
                        URI u = f.toAbsolutePath().toUri();
                        URL l = u.toURL();
                        try ( BufferedInputStream s = (BufferedInputStream) l.getContent(new Class[] { BufferedInputStream.class }) ) {
                            Severity sev = validate(s);
                            if ( sev != Severity.Violation )  {
                                if ( worstFailure != Severity.Violation )
                                    worstFailure = Severity.Warning;
                            }
                            else {
                                worstFailure = Severity.Violation;
                                // error("Violation: data did not validate");
                            }
                        }
                    }
                    catch (IOException e) {
                        log.error("There was a problem loading data file:" + f, e);
                        halt();
                    }
                });
        return worstFailure;
    }

    // load the data from either a file or a whole folder based on whatever dataFileOrFolder represents
    // this is not the normal validation mechanism but more of a batch - it might be removed at some point
    static OntModel loadData(final OntModel data, String dataFileOrFolder) throws IOException {
        registerNSPrefixes(data);

        /*** LOAD LINKED ART DATA FOR VALIDATION ***/
        Path dataFolder = pathTo(dataFileOrFolder);
        Files.walk(dataFolder)
            .filter(f -> {
                return Files.isRegularFile(f) || Files.isSymbolicLink(f);
            })
            .filter(f -> {
                return f.toString().endsWith(".json");
            })
            .forEach( 
                f -> {

                    ObjectMapper mapper = new ObjectMapper();
                    try ( JsonParser p = mapper.createParser(f.toFile() ) ) {
                        info("Loading data file " + f);

                        // try to create JSON object which will throw an exception with details about syntax errors
                        // in the event that the JSON itself is invalid
                        mapper.readTree(p);

                        if ( validateAgainstJSONSchema )
                            validateAgainstJsonSchemas( f.toAbsolutePath().toUri().toURL().getContent().toString() );

                        // when we want to load JSON LD v1.1 instead of other formats this is how we need to do it 
                        // Jena's Model.read() doesn't work directly with JSON-LD 1.1 yet
                        RDFParser.source(f.toUri().toURL().openStream())
                            .forceLang(Lang.JSONLD11)
                            .parse(data.getGraph());

                    }
                    catch (IOException | RiotException e) {
                        error("There was a problem loading data file:" + f);
                        error(e.getMessage());
                        halt();
                    }
                    catch (ValidationException ve) {
                        error("There was a problem validating a file against its json schema.");
                        for (String m : ve.getAllMessages() )
                            error(m);
                        halt();
                    }
                });

        if ( !data.listStatements().hasNext()) {
            error("No data found in: " + dataFileOrFolder);
            halt();
        }
        return data;
    }

    static ValidationEngine newEngine(Model ontology, Model shapes, Model data) {
        // SHACLPreferences.setProduceFailuresMode(true);
        ValidationEngineConfiguration configuration = new ValidationEngineConfiguration();
        configuration.setReportDetails(true);
        configuration.setValidateShapes(false);

        // merge the ontology and data together prior to validation - the AAT term hierarchy 
        // is loaded into the ontology and is needed as part of validation
        OntModel mergedModel = newOntModel();
        mergedModel.add(ontology);
        mergedModel.add(data);

        return ValidationUtil.createValidationEngine(mergedModel, shapes, configuration);
    }

    static void validateData(Model ontology, Model shapes, Model data) throws LAValidationException, InterruptedException {

        ValidationEngine engine = newEngine(ontology, shapes, data);
        LAValidationException lve = new LAValidationException();

        engine.validateAll();
        ValidationReport r = engine.getValidationReport();

        if ( ! r.conforms() ) {

            // print the RDF representation of the data graph being validated for diagnostic purposes
            if ( outputDataGraph ) {
                StringWriter sw = new StringWriter();
                data.write(sw); //, Lang.TTL.getName());
                info(lve,sw.toString());
            }

            info(lve,""); 
            for ( ValidationResult e : r.results() ) {

                //Node scc = (Node) reportField.get(e);
                info(lve,"==============================================================================================");
                String sev = e.getSeverity().toString();
                if ( sev.equals(SHACLVIOLATION ) )
                    logValidationMessage(e.getSeverity().toString(), e.getMessage(), lve);
                else
                    logValidationMessage(e.getSeverity().toString(), e.getMessage(), lve);
                info(lve,"");
                RDFNode f = e.getFocusNode();
                if ( f != null) {
                    info(lve,"Problem Node:" + f );
                    if ( f.isResource()) {
                        for ( Statement s : data.listStatements(f.asResource(), (Property) null, (String) null).toList()) {
                            info(lve,"\tnode data: " + s.getPredicate().toString() + " ==> " + s.getObject().toString());
                        }
                    }
                }
                if ( e.getPath() != null)
                    info(lve,"Path:" + e.getPath());
                if ( e.getValue() != null)
                    info(lve,"Value:" + e.getValue());
                if ( e.getSourceShape() != null)
                    info(lve,"Shape:" + e.getSourceShape());
                if ( e.getSourceConstraint() != null )
                    info(lve,"Constraint:" + e.getSourceConstraint() );
                info(lve,"==============================================================================================");
                info(lve,"");
            }
        }
        throw lve;
    }

    static OntModel newOntModel() {
        return ModelFactory.createOntologyModel(OntModelSpec.RDFS_MEM);
    }

    public static Handler getServerValidationHandler() {

        return new AbstractHandler() {

            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                
                if ( ! HttpMethod.POST.is( request.getMethod() ) )  {
                    response.sendError(
                            HttpStatus.METHOD_NOT_ALLOWED_405,
                            "Only POST method is allowed to this service and it should contain the JSON-LD to be validated.");
                    jettyRequest.setHandled(true);
                    return;
                }
                
                // in the context of a server, ignore the command line option for data graph and read from a header instead
                outputDataGraph = Boolean.parseBoolean(jettyRequest.getHeader("LAVPRINTGRAPH"));
                // see if we should output the RDF data as indicated by a special header

                // get the POSTed JSON-LD data to validate
                String jsonld =  IOUtils.toString(jettyRequest.getReader());

                try {
                    // parse the jsonld into a data model
                    OntModel dataModel = parseData(newOntModel(), jsonld);
                
                    if ( validateAgainstJSONSchema ) 
                        validateAgainstJsonSchemas(jsonld);

                    response.setContentType(ContentType.TEXT_PLAIN.toString());
                    validateData(ontologyModel, shapesModel, dataModel);
                }
                catch (LAValidationException lve) {
                    if ( lve.getSeverity() == Severity.Violation ) {
                        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY_422); 
                        response.getWriter().println("Violation");
                    }
                    // 200 response seems appropriate since there are only warnings
                    // and the content of the body provides those warnings
                    else if ( lve.getSeverity() == Severity.Warning ) {
                        response.setStatus(HttpStatus.OK_200); 
                        response.getWriter().println("Warning");
                    }
                    else {
                        response.setStatus(HttpStatus.OK_200); 
                        response.getWriter().println("Success");
                    }
                    // send the exception messages as the body of the content
                    response.getWriter().print(lve.getContents());
                }
                catch (InterruptedException e) {
                    log.error(e.getMessage(),e);
                }
                finally {
                    jettyRequest.setHandled(true);
                }
            }
        };

    }

    public static void startServer(Handler handler) throws Exception {
        
        // Create and configure a ThreadPool.
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("server");

        // Create a Server instance.
        Server server = new Server(threadPool);

        // Create a ServerConnector to accept connections from clients.
        URL url = serviceURL == null ? new URL(defaultServiceURL) : new URL(serviceURL);
        String proto = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort();
        String baseURL = proto + "//" + host + ":" + port;

        ServerConnector connector = new ServerConnector(server);
        connector.setHost(url.getHost());
        connector.setPort(url.getPort());

        // Add the Connector to the Server
        server.addConnector(connector);
        
        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]
                { new ShutdownHandler(serverSecret, true, false), handler } );
        server.setHandler(handlers);

        try {
            server.start();
            info("Started validation service at " + baseURL );
            info("To exit server, use lav -k or send via web request, e.g. curl -X POST " + baseURL + "/shutdown?token=quit");
            server.join();
        } 
        catch (Exception e) {
            log.error("Validation service encountered a problem and could not start or has been shut down", e);
        }
        finally {
            server.stop();
            server.destroy();
            info("Shutting down the validation service at " + baseURL);
        }

    }
    
    public static void shutdownServer() throws Exception {
        HttpClient httpClient = getClient();
        String url = serviceURL + "/shutdown";
        info("Sending shutdown command: " + url);
        Fields fields = new Fields();
        fields.put("token", serverSecret);
        org.eclipse.jetty.client.api.Request request = httpClient.POST(url).body(new FormRequestContent(fields));
        ContentResponse content = request.send();
        info(content.getContentAsString());
        httpClient.stop();
        httpClient.destroy();
    }
    
    @Override
    public Integer call() throws Exception { 
        if ( stopServer ) {
            shutdownServer(); 
            return 0;
        }
        if ( startServer || serviceURL == null ) {
            // load the ontology and shapes required for validating against linked art
            ontologyModel = loadOntology(newOntModel(), ontologyModelFile);
            shapesModel = loadShapes(newOntModel(), shapesFileOrFolder);
        }

        // if we are running as a server, that's all we're going to do
        if ( startServer ) {
            startServer(getServerValidationHandler());
            return 0;
        }

        // if the input is a URL, fetch the contents and attempt to validate it
        try {
            URL url = new URL(dataSource);
            info("Attempting to fetch and validate content at URL: " + url);
            try ( InputStream is = url.openStream() ) {
                Severity sev = validate(is);
                return sev == Severity.Violation ? 1 : 0;
            }
        }
        catch (MalformedURLException e) {
            // otherwise, check for stdin 
            if ( dataSource.equals("-") ) {
                info("Reading from standard input...");
                Severity sev = validate(System.in);
                return sev == Severity.Violation ? 1 : 0;
            }
            // otherwise assume it's a path
            else {
                info("Finding all JSON files under path " + dataSource);
                // and proceed to validate each located file against the validation service
                Severity sev = validateByPath(dataSource);
                return sev == Severity.Violation ? 1 : 0;
            }
        }
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new Validator()).execute(args);
        System.exit(exitCode);
    }


}