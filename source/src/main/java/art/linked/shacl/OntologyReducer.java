/**
* LinkedArtOntologyReducer - a tool for minimizing the ontology requirements for linked art
* Reads in the CRM and Linked Art ontologies, discards unused classes and properties then 
* compacts into TTL which is arguably the most size-efficient format of human readable triples
* @author      Dave Beaudet
* @version     0.1
* @since       0.1
*/
package art.linked.shacl;


import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import net.minidev.json.JSONArray;

import org.topbraid.jenax.util.JenaUtil; 

/******************************************************************************************************************
 * @author beaudet
 */
public class OntologyReducer {
	
	public static void addSuperClasses(Resource r, Set<Resource> visitedSet) {
		
		if ( r == null || !r.canAs(OntClass.class) ) {
		//	System.out.println("top reached");
			return;
		}
		
		visitedSet.add(r);
		OntClass c = r.as(OntClass.class);
		//System.out.println("Node:" + c);
		for (OntClass d : c.listSuperClasses().toList()) {
		//	System.out.println("Super:" + d);
			addSuperClasses(d.asResource(), visitedSet);
		}
	}

	public static void main(String args[]) throws MalformedURLException, IOException {
		// Load the CIDOC-CRM into an OntModel.  We won't add any reasoning capability to this model, but
		// we'll use it as a submodel of OntModels that do.  Jena can pull the document from the web, but
		// one can also download a local copy and read that.  It would certainly a bit quicker than 
		// downloading it every time although this operation is rarely run so seems unnecessary.

		OntModel laModel = JenaUtil.createOntologyModel(OntModelSpec.RDFS_MEM, null);
		laModel.setNsPrefix("crm", "http://www.cidoc-crm.org/cidoc-crm/");

		// this context is automatically loaded when Linked Art JSON-LD is loaded by Jena which does the 
		// translation to CRM Classes: https://linked.art/ns/v1/linked-art.json
		laModel.read( "https://raw.githubusercontent.com/linked-art/crom/master/utils/data/cidoc.xml" );
		laModel.read( "https://raw.githubusercontent.com/linked-art/crom/master/utils/data/linkedart_crm_enhancements.xml" );
		laModel.read( "https://raw.githubusercontent.com/linked-art/crom/master/utils/data/linkedart.xml" );

		// load a list of classes and properties from the linked art site that indicate whether those classes and properties
		// are actually used and if they are used whether there can be more than one instance present in the graph
		DocumentContext dc = 
				JsonPath.parse(
						new URL("https://raw.githubusercontent.com/linked-art/crom/master/cromulent/data/crm-profile.json").openStream() );
		Map<String, Object> reqRes = dc.read("$");

		// load JSON of properties and classes we actually need from the Linked Art JSON file

		// Properties and Classes are resources which are subjects
		Set<Resource> usedResources = new HashSet<Resource>();
		Set<Resource> unusedResources = new HashSet<Resource>();
		for ( Resource r : laModel.listSubjects().toList() ) {
			String resourceLocalName = r.getLocalName();
			String resourceNS = r.getNameSpace();
			String resourcePrefixed = laModel.getNsURIPrefix(resourceNS) + ":" + resourceLocalName;
			Object n = reqRes.get(resourceLocalName);
			if ( n == null )
				n = reqRes.get(resourcePrefixed);
			if ( n == null ) {
				System.out.println("null here");
			}
			int v=0; //,w=0;
			boolean used = false; //, multValOk = false;
			if ( n instanceof JSONArray) {
				JSONArray a = (JSONArray) n;
				v = (Integer) a.get(0);
			}
			else
				v = (Integer) n;

			used = v == 1 ? true : false;
			
			if ( !used || n == null ) {
				unusedResources.add(r);
			}
			else {
				// add the resource to the used list
				addSuperClasses(r, usedResources);
			}
		}
		
		// remove any superclasses from the list of unused classes
		for ( Resource r: usedResources) {
			unusedResources.remove(r);
		}

		for ( Resource r : unusedResources ) {
			if ( r.canAs(OntResource.class)) {
				r.as(OntResource.class).remove();
			}
		}

		// write the resulting ontology to the console in TTL format
		laModel.write(System.out, Lang.TTL.getName());
		
	}
}
