package org.apache.ctakes.rt_parser.ae;

import org.apache.ctakes.core.pipeline.PipeBitInfo;
import org.apache.ctakes.core.util.log.DotLogger;
import org.apache.ctakes.typesystem.type.textspan.Paragraph;
import org.apache.ctakes.typesystem.type.textspan.Sentence;
import org.apache.log4j.Logger;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import java.io.IOException;

/**
 * @author SPF , chip-nlp
 * @since {8/29/2022}
 */
@PipeBitInfo(
        name = "ParagraphSentencer",
        description = "Creates a sentence covering each paragraph.",
        role = PipeBitInfo.Role.ANNOTATOR,
        dependencies = PipeBitInfo.TypeProduct.PARAGRAPH,
        products = PipeBitInfo.TypeProduct.SENTENCE
)
public class ParagraphSentencer extends JCasAnnotator_ImplBase {

    static private void addSentence( final JCas jcas, final Paragraph paragraph ) {
        final Sentence sentence = new Sentence( jcas, paragraph.getBegin(), paragraph.getEnd() );
        sentence.addToIndexes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void process( final JCas jcas ) throws AnalysisEngineProcessException {
        JCasUtil.select( jcas, Paragraph.class ).forEach( p -> addSentence( jcas, p ) );
    }

}
