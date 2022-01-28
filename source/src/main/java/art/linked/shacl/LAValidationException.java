/**
* LAValidationException is an exception class that doubles as 
* a notebook for the linked art validator. 
* @author      Dave Beaudet
* @version     0.1
* @since       0.1
*/
package art.linked.shacl;

import java.io.StringWriter;

import org.apache.jena.shacl.validation.Severity;

public class LAValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    StringWriter messages = new StringWriter();
    
    Severity severity = Severity.Info;

    public String getContents() {
        return messages.toString();
    }

    private void addMessage(String s) {
        messages.append(s+"\n");
    }
    
    public void info(String s) {
        addMessage(s);
    }
    public void warn(String s) {
        addMessage(s);
        if ( severity.equals(Severity.Info))
            setSeverity(Severity.Warning);

    }
    public void error(String s) {
        addMessage(s);
        if ( !severity.equals(Severity.Violation))
            setSeverity(Severity.Violation);
    }
    
    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity sev) {
        severity = sev;
    }

}
