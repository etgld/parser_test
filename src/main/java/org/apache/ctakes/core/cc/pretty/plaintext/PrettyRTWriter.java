package org.apache.ctakes.core.cc.pretty.plaintext;

import org.apache.ctakes.core.util.Pair;
import org.apache.ctakes.core.util.doc.DocIdUtil;
import org.apache.ctakes.typesystem.type.textsem.ProcedureMention;
import org.apache.ctakes.typesystem.type.textspan.Sentence;
import org.apache.log4j.Logger;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Note level RT annotations output for the RT Docker
 *
 * @author Eli , chip-nlp
 * @version %I%
 * @since 3/13/2023
 */
final public class PrettyRTWriter extends JCasAnnotator_ImplBase {


   static private final Logger LOGGER = Logger.getLogger( "PrettyRTWriter" );
   static private final String FILE_EXTENSION = ".txt";
   static private int _current_procedure = 0;

   private String _outputDirPath;

   /**
    * @param outputDirectoryPath may be empty or null, in which case the current working directory is used
    * @throws IllegalArgumentException if the provided path points to a File and not a Directory
    * @throws SecurityException        if the File System has issues
    */
   public void setOutputDirectory( final String outputDirectoryPath ) throws IllegalArgumentException,
                                                                             SecurityException {
      // If no outputDir is specified (null or empty) the current working directory will be used.  Else check path.
      if ( outputDirectoryPath == null || outputDirectoryPath.isEmpty() ) {
         _outputDirPath = "";
         LOGGER.debug( "No Output Directory Path specified, using current working directory "
                       + System.getProperty( "user.dir" ) );
         return;
      }
      final File outputDir = new File( outputDirectoryPath );
      if ( !outputDir.exists() ) {
         outputDir.mkdirs();
      }
      if ( !outputDir.isDirectory() ) {
         throw new IllegalArgumentException( outputDirectoryPath + " is not a valid directory path" );
      }
      _outputDirPath = outputDirectoryPath;
      LOGGER.debug( "Output Directory Path set to " + _outputDirPath );
   }

   /**
    * Process the jcas and write pretty sentences to file.  Filename is based upon the document id stored in the cas
    *
    * @param jcas ye olde ...
    */
   public void process( final JCas jcas ) {
      LOGGER.info( "Starting processing" );
      final String docId = DocIdUtil.getDocumentIdForFile( jcas );
      File outputFile;
      if ( _outputDirPath == null || _outputDirPath.isEmpty() ) {
         outputFile = new File( docId + FILE_EXTENSION );
      } else {
         outputFile = new File( _outputDirPath, docId + FILE_EXTENSION );
      }
      writeFile( jcas, outputFile.getPath() );
      LOGGER.info( "Finished processing" );
   }

   public void writeFile( final JCas jCas, final String outputFilePath ) {
      try ( final BufferedWriter writer = new BufferedWriter( new FileWriter( outputFilePath ) ) ) {
         Map<Annotation, Collection<Sentence>> sentIndex = JCasUtil.indexCovering(jCas, Annotation.class, Sentence.class);
         final List<ProcedureMention> procedureMentions = JCasUtil
                 .select( jCas, ProcedureMention.class )
                 .stream()
                 .sorted(
                         Comparator.comparing(
                                 ProcedureMention :: getBegin
                         )
                 )
                 .collect(Collectors.toList());
         for ( ProcedureMention procedureMention : procedureMentions ) {
            Sentence container = sentIndex.get(procedureMention).iterator().next();
            writeMention( container, procedureMention, writer );
         }
      } catch ( IOException ioE ) {
         LOGGER.error( "Could not not write csv file " + outputFilePath );
         LOGGER.error( ioE.getMessage() );
      }
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
           final Sentence container,
           final ProcedureMention procedureMention,
           final BufferedWriter writer
   ) throws IOException {

      // there HAS to be a better way
      // (would be trivial if cTAKES returned a null instead of throwing a fit)
      // to do this but for now:


      writer.write(String.format("\n%d.\t", _current_procedure));
      _current_procedure++;

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
              container
                      .getCoveredText()
                      .replace("\n", " "),
              labelToInds
         )
      );
      writer.newLine();
   }

   static private String taggedSentence(String sentenceText, Map<Pair<Integer>, String> labelToInds){
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

      int previous = 0;

      for (Pair<Integer> indices : orderedInds){
         String tag = labelToInds.get(indices);
         out.append(sentenceText, previous, indices.getValue1());
         out.append(String.format("<%s>", tag));
         out.append(sentenceText, indices.getValue1(), indices.getValue2());
         out.append(String.format("</%s>", tag));
         previous = indices.getValue2();
      }

      return out.toString();
   }
}
