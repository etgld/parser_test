package org.apache.ctakes.examples.cc;

import org.apache.ctakes.core.cc.AbstractTableFileWriter;
import org.apache.ctakes.core.pipeline.PipeBitInfo;
import org.apache.ctakes.typesystem.type.textsem.*;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.apache.ctakes.core.pipeline.PipeBitInfo.TypeProduct.*;

/**
 * @author SPF , chip-nlp
 * @since {3/16/2023}
 */
@PipeBitInfo(
        name = "Procedure Table Writer",
        description = "Writes a table of Procedure information to file, sorted by character index.",
        role = PipeBitInfo.Role.WRITER,
        dependencies = { DOCUMENT_ID, IDENTIFIED_ANNOTATION },
        usables = { DOCUMENT_ID_PREFIX }
)
public class RTTableFileWriter extends AbstractTableFileWriter {

    // C5688326 is the umls cui for "Radiotherapy Treatment Phase".
    static private final String RT_CUI = "C5688326";
    static private final String NO_ANNOTATION = "None";
    static private final AtomicInteger COUNTER = new AtomicInteger( 0 );

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<String> createHeaderRow( final JCas jCas ) {
        return Arrays.asList(
                " procedure_number ",
                " central_dose ",
                " boost ",
                " date ",
                " secondary_dose ",
                " fraction_frequency ",
                " fraction_number ",
                " site " );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<List<String>> createDataRows( final JCas jCas ) {
        COUNTER.set( 0 );
        // Using dumb filter on text "dose" until we set the CUI.
        return JCasUtil.select( jCas, ProcedureMention.class )
                .stream()
                .filter( p -> p.getCoveredText().toLowerCase().contains( "dose" ) )
//            .filter( p -> IdentifiedAnnotationUtil.getCuis( p ).contains( RT_CUI ) )
                .sorted( Comparator.comparingInt( Annotation::getBegin ) )
                .map( RTTableFileWriter.ModifierRow::new )
                .map( RTTableFileWriter.ModifierRow::getColumns )
                .collect( Collectors.toList() );
    }


    /**
     * Simple container for annotation information.
     */
    static private class ModifierRow {
        private final String _procedureNumber;
        private final String _centralDose;
        private final String _boost;
        private final String _date;
        private final String _secondaryDose;
        private final String _fractionFrequency;
        private final String _fractionNumber;
        private final String _site;

        private ModifierRow( final ProcedureMention rt ) {
            _procedureNumber = COUNTER.incrementAndGet() + "";
            _centralDose = getOffsets( rt );
            _boost = getOffsets( rt.getStatusChange() );
            _date = getOffsets( rt.getStartTime() );
            _secondaryDose = getOffsets( rt.getTotalDose() );
            _fractionFrequency = getOffsets( rt.getFrequency() );
            _fractionNumber = getOffsets( rt.getDosageCount() );
            _site = getOffsets( rt.getAnatomicalSite() );
        }

        public List<String> getColumns() {
            return Arrays.asList(
                    _procedureNumber,
                    _centralDose,
                    _boost,
                    _date,
                    _secondaryDose,
                    _fractionFrequency,
                    _fractionNumber,
                    _site );
        }

        private String getOffsets( final Annotation annotation ) {
            return ( annotation == null ) ? NO_ANNOTATION : annotation.getBegin() + "_" + annotation.getEnd();
        }

    }

}