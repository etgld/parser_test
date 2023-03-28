package org.apache.ctakes.examples.cc;

import org.apache.ctakes.core.cc.AbstractJCasFileWriter;
import org.apache.ctakes.core.util.Pair;
import org.apache.ctakes.typesystem.type.textsem.ProcedureMention;
import org.apache.ctakes.typesystem.type.textspan.Sentence;
import org.apache.log4j.Logger;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Note level RT annotations output for the RT Docker
 *
 * @author Eli , chip-nlp
 * @version %I%
 * @since 3/13/2023
 */
final public class RTProcedureTextWriter extends AbstractJCasFileWriter {


    static private final Logger LOGGER = Logger.getLogger( "RTProcedureTextWriter" );
    static private final String FILE_EXTENSION = ".txt";


    @Override
    public void writeFile( final JCas jCas,
                           final String outputDir,
                           final String documentId,
                           final String fileName ) throws IOException {
        final File outputFilePath = new File( outputDir , fileName + "_procedure_tags" + FILE_EXTENSION );
        LOGGER.info("Writing " + fileName + FILE_EXTENSION + " to " + outputFilePath.getPath()  +" ...") ;
        try ( Writer writer = new BufferedWriter( new FileWriter( outputFilePath ) ) ) {
            Map<Sentence, Collection<ProcedureMention>> sent2ColProcs = JCasUtil.indexCovered(jCas, Sentence.class, ProcedureMention.class);
            Map<Sentence, List<ProcedureMention>> sent2Procs = new HashMap<>();
            sent2ColProcs
                    .keySet()
                    .forEach(
                    sentence -> sent2Procs.put(
                            sentence,
                            sent2ColProcs
                                    .get(sentence)
                                    .stream()
                                    .sorted(
                                            Comparator.comparingInt( ProcedureMention::getBegin )
                                    ).collect(Collectors.toList())
                    )
            );
            List<Sentence> casSentences = JCasUtil.select(jCas, Sentence.class)
                    .stream()
                    .sorted(
                            Comparator.comparingInt(
                                    Sentence :: getBegin
                            )
                    ).collect(Collectors.toList());

            casSentences
                    .stream()
                    .filter(
                            sent2Procs::containsKey
                    )
                    .forEach(
                    sentence -> sent2Procs.get(sentence)
                            .forEach(
                                    mention -> {
                                        try {
                                            writeMention(
                                                    casSentences.indexOf(sentence) + 1,
                                                    sent2Procs.get(sentence).indexOf(mention) + 1,
                                                    sentence,
                                                    mention,
                                                    writer
                                            );
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                            )
            );
        }
        LOGGER.info( "Finished Writing" );
    }


    static public Pair<Integer> getSpan( final Annotation attribute ){
        return new Pair<>(attribute.getBegin(), attribute.getEnd());
    }


    /**
     * Write a sentence from the document text
     *
     * @param container sentence containing the annotation
     * @param procedureMention annotation containing the procedure mention
     * @param writer   writer to which pretty text for the sentence should be written
     * @throws IOException if the writer has issues
     */
    static public void writeMention(
            final int sentIndex,
            final int localProcIndex,
            final Sentence container,
            final ProcedureMention procedureMention,
            final Writer writer
    ) throws IOException {

        // there HAS to be a better way to do this
        // (would be trivial if cTAKES returned a null instead of throwing a fit)
        // but for now:


        writer.write(
                String.format(
                        "\nParagraph: %d , ProcedureMention: %d\n\n",
                        sentIndex,
                        localProcIndex
                )
        );


        Map<Pair<Integer>, String> labelToInds = new HashMap<>();
        // central dose
        try {
            labelToInds.put(getSpan(procedureMention), "central-dose");
        } catch (Exception ignored){
        }
        // boost
        try {
            labelToInds.put(getSpan(procedureMention.getStatusChange()), "boost");
        } catch (Exception ignored){
        }

        // date
        try {
            labelToInds.put(getSpan(procedureMention.getStartTime()), "date");
        } catch (Exception ignored){
        }

        // secondary dose
        try {
            labelToInds.put(getSpan(procedureMention.getTotalDose()), "secondary-dose");
        } catch (Exception ignored){
        }

        // fraction frequency
        try {
            labelToInds.put(getSpan(procedureMention.getFrequency()), "fx-freq");
        } catch (Exception ignored){
        }

        // fraction number
        try {
            labelToInds.put(getSpan(procedureMention.getDosageCount()), "fx-no");
        } catch (Exception ignored){
        }

        // site
        try {
            labelToInds.put(getSpan(procedureMention.getAnatomicalSite()), "site");
        } catch (Exception ignored){
        }

        writer.write(
                taggedSentence(
                        container,
                        labelToInds
                )
        );
        writer.write("\n");
    }

    static private String taggedSentence(Sentence sentence, Map<Pair<Integer>, String> labelToInds){
        StringBuilder out = new StringBuilder();
        List<Pair<Integer>> orderedInds = labelToInds
                .keySet()
                .stream()
                .sorted(
                        Comparator
                                .comparing(
                                        Pair::getValue1
                                )
                ).collect(
                        Collectors.toList()
                );

        String sentenceText = sentence.getCoveredText().replace("\n", " ");
        int sentenceBegin = sentence.getBegin();
        int previous = 0;

        for (Pair<Integer> indices : orderedInds){
            String tag = labelToInds.get(indices);
            int localBegin = indices.getValue1() - sentenceBegin;
            int localEnd = indices.getValue2() - sentenceBegin;
            out.append(sentenceText, previous, localBegin);
            out.append(String.format("<%s>", tag));
            out.append(sentenceText, localBegin, localEnd);
            out.append(String.format("</%s>", tag));
            previous = localEnd;
        }

        out.append(sentenceText, previous, sentenceText.length());

        return out.toString();
    }
}
