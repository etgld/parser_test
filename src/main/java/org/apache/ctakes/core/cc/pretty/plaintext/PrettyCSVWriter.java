package org.apache.ctakes.core.cc.pretty.plaintext;

import org.apache.ctakes.core.util.doc.DocIdUtil;
import org.apache.ctakes.typesystem.type.textsem.ProcedureMention;
import org.apache.log4j.Logger;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


/**
 * Note level RT annotations output for the RT Docker
 *
 * @author Eli , chip-nlp
 * @version %I%
 * @since 3/10/2023
 */
final public class PrettyCSVWriter extends JCasAnnotator_ImplBase {


   static private final Logger LOGGER = Logger.getLogger( "PrettyCSVWriter" );
   static private final String FILE_EXTENSION = ".csv";
   // static private int _current_procedure = 0;

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
         // csv columns
         // writer.write("procedure_number,central_dose,boost,date,secondary_dose,fraction_frequency,fraction_number,site");
         writer.write("central_dose,boost,date,secondary_dose,fraction_frequency,fraction_number,site");
         writer.newLine();
         JCasUtil.select( jCas, ProcedureMention.class )
                 .stream()
                 .sorted(
                         Comparator.comparing(
                                 ProcedureMention :: getBegin
                         )
                 )
                 .forEach(p -> {
                    try {
                       writeMention(p, writer);
                    } catch (IOException e) {
                       // don't know why we didn't have to
                       // deal with an exception possibility in the for loop but whatever
                       throw new RuntimeException(e);
                    }
                 });
      } catch ( IOException ioE ) {
         LOGGER.error( "Could not not write csv file " + outputFilePath );
         LOGGER.error( ioE.getMessage() );
      }
   }

   static public String getSpan( final Annotation attribute ){
      return String.format(
              "%d_%d,",
              attribute.getBegin(),
              attribute.getEnd()
              );
   }


   /**
    * Write a sentence from the document text
    *
    * @param procedureMention annotation containing the procedure mention
    * @param writer   writer to which pretty text for the sentence should be written
    * @throws IOException if the writer has issues
    */
   static public void writeMention(
           final ProcedureMention procedureMention,
           final BufferedWriter writer
   ) throws IOException {

      // there HAS to be a better way
      // (would be trivial if cTAKES returned a null instead of throwing a fit)
      // to do this but for now:
      String youGotTheDud = "None,"; //or maybe -1_-1 if one runs into Pandas issues

      // writer.write(String.format("%d,", _current_procedure));
      // _current_procedure++;

      // central dose
      try {
         writer.write(getSpan(procedureMention));
      } catch (Exception e){
         // this should NOT happen, need to find the right exception
         writer.write(youGotTheDud);
      }

      // boost
      try {
         writer.write(getSpan(procedureMention.getStatusChange()));
      } catch (Exception e){
         writer.write(youGotTheDud);
      }

      // date
      try {
         writer.write(getSpan(procedureMention.getStartTime()));
      } catch (Exception e){
         writer.write(youGotTheDud);
      }

      // secondary dose
      try {
         writer.write(getSpan(procedureMention.getTotalDose()));
      } catch (Exception e){
         writer.write(youGotTheDud);
      }

      // fraction frequency
      try {
         writer.write(getSpan(procedureMention.getFrequency()));
      } catch (Exception e){
         writer.write(youGotTheDud);
      }

      // fraction number
      try {
         writer.write(getSpan(procedureMention.getDosageCount()));
      } catch (Exception e){
         writer.write(youGotTheDud);
      }

      // site
      try {
         writer.write(getSpan(procedureMention.getAnatomicalSite()));
      } catch (Exception e){
         writer.write(youGotTheDud);
      }
      writer.newLine();
   }
}
