/**
* Tests - JUnit tests for the linked art validator and its shapes
* Executes validation against all data under the <install dir>/tests folder and tracks coverage
* of all shapes by the test data and coverage of the test data against all SHACL shapes, making
* it much easier to understand where gaps exist in tests.
* @author      Dave Beaudet
* @version     0.1
* @since       0.1
*/
package art.linked.shacl;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.RiotException;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.topbraid.shacl.validation.ValidationEngine;
import org.topbraid.shacl.validation.ValidationReport;
import org.topbraid.shacl.validation.ValidationResult;

import static art.linked.shacl.Validator.*;

public class Tests {
    
    private static final Logger log = LoggerFactory.getLogger(Tests.class);
    
    static boolean isExpectedValidationError(ValidationResult e, Map<String,String> testCoverage) {

        String testNum = null;
        String message = e.getMessage();
        Matcher m = constraintIDPattern.matcher(message);
        if ( m.matches() )
            testNum = m.group(1);

        if ( testNum == null ) {
            log.error("Could not determine test case number from SHACL message in: " + e.getMessage());
            halt();
        }

        // so, if we don't care about this particular error, just log it for informational purposes and move to the next one
        if ( !testCoverage.containsKey(testNum) ) {
            log.info("ignoring: " + e.getMessage());
            return true;
        }

        // otherwise, check to ensure the severity matches

        String vSev = e.getSeverity().getLocalName().toLowerCase().substring(0,1);
        String eSev = testNum.toLowerCase().substring(0,1);

        try {
            assertTrue(vSev.equals(eSev));
            testCoverage.put(testNum, testNum);
        } 
        catch (AssertionError a) {
            log.info("");
            log.info("==================================================================");
            log.info("Severity:" + e.getSeverity() );
            log.info("Message:" + e.getMessage() );
            log.info("Focus:" + e.getFocusNode() );
            log.info("Shape:" + e.getSourceShape() );
            log.info("Value:" + e.getValue() );
            log.info("Path:" + e.getPath() );
            log.info("==================================================================");
            log.error("JUnit Error: The severity (" + eSev + ") for test number + " + testNum + " did not match the SHACL validation results above.");
            throw a;
        }
        return true;
    }

    static Map<String,String> executeTest(Model ontology, Model shapes, Model baseData, String path, boolean baseDataOnly) {
        Map<String, String> testCoverage = new HashMap<>();
        try {
            OntModel data = newOntModel();
            if ( !baseDataOnly )
                loadData(data, path);

            // add baseline data to model we're about to validate with JUnit
            data.add(baseData);

            // in our test cases, locate the special node ('note') that stores all of the test conditions this file should be depended on for coverage
            Property p = data.getProperty("http://www.cidoc-crm.org/cidoc-crm/P3_has_note");
            for ( Statement s : data.listStatements((Resource) null, p, (String) null).toList() ) {
                String note = s.getObject().toString();
                for ( String testNum : note.split(",") )
                    testCoverage.put(testNum, null);
            }
            if ( !baseDataOnly && testCoverage.size() < 1) {
                log.error("Please populate test coverage by listing comma delimited test case numbers into a 'note' property of the top level object in the file " + path);
                halt();
            }

            // at this point, dataModel contains all data that needs to be validated, so proceed
            ValidationEngine engine = newEngine(ontology, shapes, data);
            engine.validateAll();
            ValidationReport r = engine.getValidationReport();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // output the data graph first with any errors listed below
            if ( baseDataOnly != r.conforms() && outputDataGraph ) {
                data.write(baos);
                log.info(baos.toString());
            }
            // list the results of the validation tests 
            for ( ValidationResult e : r.results() ) {
                if ( !baseDataOnly) 
                    isExpectedValidationError(e, testCoverage);
                else 
                    logValidationMessage(e.getSeverity().toString(), e.getMessage(), null);
            }
            if ( !baseDataOnly && r.conforms() )
                log.error("This JUnit test data file is not supposed to validate in the context of itself plus base data but it does: " + path + " ");
            else if ( baseDataOnly && !r.conforms() )
                log.error("The JUnit base data file is supposed to validate but it does not.");
            // graph should validate only if base data is valid and individual shacl test data files are invalid
            assertTrue(r.conforms() == baseDataOnly);

            // finally, report on any expected tests that were not triggered by the file even though they were supposed to be triggered
            if ( !baseDataOnly ) {
                boolean sentOutput = false;
                for ( String k : testCoverage.keySet()) {
                    String v = testCoverage.get(k);
                    if ( v == null ) {
                        if ( !sentOutput && outputDataGraph ) {
                            data.write(baos);
                            log.info("Data Graph Contents:\n"+baos.toString());
                        }
                        sentOutput = true;
                        log.error("File " + path + " did not result in a constraint violation for test case " + k);
                    }
                    assertTrue( v != null );
                }
            }
        } catch (IOException | RiotException e) {
            log.error("There was a problem loading a data file." + e);
            log.error(e.getMessage());
            halt();
        } catch (InterruptedException e1) {
            log.error("Interrupted", e1);
            halt();
        }
        return testCoverage;
    }

    
    @Test
    public void testAllSHACLConstraints() throws IOException, InterruptedException {

        log.info("Testing SHACL shapes against junit test data");

        OntModel ontologyModel = loadOntology(newOntModel(), ontologyModelFile);
        OntModel shapesModel = loadShapes(newOntModel(), shapesFileOrFolder);
        OntModel baseDataModel = loadData(newOntModel(), pathTo(INSTALLDIR,"tests/valid/") );

        // load the base data which should validate, and confirm it validates first
        executeTest(ontologyModel, shapesModel, baseDataModel, null, true);

        Map<String,String> passedTests = new HashMap<>();

        ValidationEngine engine = newEngine(ontologyModel, shapesModel, baseDataModel);
        engine.validateAll();
        ValidationReport r = engine.getValidationReport();
        assertTrue(r.conforms());

        // load each test shape one at a time and validate it and the base data together 
        Path dataFolder = pathTo(INSTALLDIR+"tests/invalid/");
        Files.walk(dataFolder)
        .filter(f -> {
            return Files.isRegularFile(f) || Files.isSymbolicLink(f);
        })
        .filter(f -> {
            return f.toString().endsWith(".json");
        })
        .forEach( f -> { 
            // keep track of all test numbers that have coverage with JUnit testing and report that as well
            // in case we're missing any tests that need to be created to assure 100% coverage of all shape constraints
            passedTests.putAll( executeTest(ontologyModel, shapesModel, baseDataModel, f.toString(), false) );
        });

        // finally, grab all test numbers from existing shapes and see if we have coverage in the passing tests
        // report on any gaps
        List<String> missedConstraints = new ArrayList<>();
        List<String> allConstraints = new ArrayList<>();
        for ( Statement s : shapesModel.listStatements((Resource) null, shapesModel.getProperty("http://www.w3.org/ns/shacl#message"), (String) null ).toList() ) {
            String message = s.getObject().toString();

            Matcher m = constraintIDPattern.matcher(message);
            if ( m.matches() ) {
                String testNum = m.group(1);
                if ( allConstraints.contains(testNum)) {
                    log.error("Duplicate test number detected in SHACL shapes: " + testNum);
                    halt();
                }
                allConstraints.add(testNum);
                if ( ! passedTests.containsKey(testNum) )
                    missedConstraints.add(testNum);
            }
            else {
                log.error("sh:message is missing a test case number: " + message);
                assertTrue(m.matches());
            }

        }
        Collections.sort(missedConstraints);
        for ( String testNum : missedConstraints)
            log.warn("SHACL test missing for constraint: " + testNum);

        assertTrue( missedConstraints.size() == 0 );
        log.info("Success: All JUnit tests passed.");
    }

}
