package org.apache.ctakes.rt_parser.ae;

import org.apache.ctakes.core.ae.PythonRunner;
import org.apache.ctakes.core.pipeline.PipeBitInfo;
import org.apache.log4j.Logger;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import java.io.IOException;

/**
 * @author DJ (then Eli), chip-nlp
 * @since {3/28/2023}
 */
@PipeBitInfo(
        name = "RTPipper",
        description = "Will pip rt_parser based upon user request.",
        role = PipeBitInfo.Role.SPECIAL
)
public class RTPipper extends PythonRunner {

    static private final Logger LOGGER = Logger.getLogger( "RTPipper" );
    // to add a configuration parameter, type "param" and hit tab.

    static public final String PIP_RT_PARAM = "PipRT";
    static public final String PIP_RT_DESC = "pip or do not pip rt_parser.  Default is yes.";
    @ConfigurationParameter(
            name = PIP_RT_PARAM,
            description = PIP_RT_DESC,
            mandatory = false,
            defaultValue = "yes"
    )
    private String _pipRT;


    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize( final UimaContext context ) throws ResourceInitializationException {
        super.initialize( context );
    }

    /**
     * Does nothing.
     * {@inheritDoc}
     */
    @Override
    public void process( final JCas jcas ) throws AnalysisEngineProcessException {
    }

    private boolean doPip() {
        return _pipRT.isEmpty()
                || _pipRT.equalsIgnoreCase( "yes" )
                || _pipRT.equalsIgnoreCase( "true" );
    }

    protected boolean isCommandMandatory() {
        return false;
    }

    /**
     *
     * @return false as we only want to do this on initialization.
     */
    protected boolean processPerDoc() {
        return false;
    }

    /**
     *
     * @return the pip command to install the RT parser module.
     */
    protected String getCommand() {
        return "-m pip install resources/org/apache/ctakes/rt_parser/rt_parser_py/";
    }

    /**
     *
     * @return true
     */
    protected boolean shouldWait() {
        return true;
    }

    /**
     * Only run if _pipRT is yes.
     * @throws IOException -
     */
    protected void runCommand() throws IOException {
        if ( doPip() ) {
            LOGGER.info( "Since rt_parser is pip installed from source, pip will always perform an install." );
            LOGGER.info( "To turn off the pip use \"set " + PIP_RT_PARAM + "=no\" in your piper file" );
            LOGGER.info( " or add \"--pipRT no\" to your command line." );
            super.runCommand();
        }
    }


}
