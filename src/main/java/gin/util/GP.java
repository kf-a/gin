package gin.util;

import java.io.IOException;
import java.io.File;
import java.io.FileWriter;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Random;

import com.opencsv.CSVWriter;
import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;
import org.apache.commons.rng.simple.JDKRandomBridge;
import org.apache.commons.rng.simple.RandomSource;
import org.pmw.tinylog.Logger;

import gin.Patch;
import gin.SourceFile;
import gin.edit.Edit;
import gin.edit.Edit.EditType;
import gin.test.UnitTest;
import gin.test.UnitTestResultSet;


/**
 * Method-based General GP search.
 *
 */

public abstract class GP extends Sampler {
    
    @Argument(alias = "et", description = "Edit type: this can be a member of the EditType enum (LINE,STATEMENT,MATCHED_STATEMENT,MODIFY_STATEMENT); the fully qualified name of a class that extends gin.edit.Edit, or a comma separated list of both")
    protected String editType = EditType.STATEMENT.toString();

    @Argument(alias = "gn", description = "Number of generations")
    protected Integer genNumber = 1;

    @Argument(alias = "in", description = "Number of individuals")
    protected Integer indNumber = 10; 

    @Argument(alias = "ms", description = "Random seed for mutation operator selection")
    protected Integer mutationSeed = 123;

    @Argument(alias = "is", description = "Random seed for individual selection")
    protected Integer individualSeed = 123;
    
    @Argument(alias = "hom", description = "Toggle higher-order mutations in GP")
    protected boolean homEnabled = false;
    
    @Argument(alias = "pn", description = "Patch number for finding FOMs in RandomSampler")
    protected Integer patchNumber = 100;
    
    @Argument(alias = "homsize", description = "Number of FOMs to combine for HOM in initial HOM population")
    protected Integer homSize = 2;

    // Elite size
    @Argument(alias = "e", description = "Number of elites for evolutionary search")
    protected Integer eliteSize = 10;
    
    // Allowed edit types for sampling: parsed from editType
    protected List<Class<? extends Edit>> editTypes;

    protected Random mutationRng;
    protected Random individualRng;

    public GP(String[] args) {
        super(args);
        Args.parseOrExit(this, args);
        setup();
        printAdditionalArguments();
    }

    // Constructor used for testing
    public GP(File projectDir, File methodFile) {
        super(projectDir, methodFile);
        setup();
    }

    private void printAdditionalArguments() {
        Logger.info("Edit types: "+ editTypes);
        Logger.info("Number of generations: "+ genNumber);
        Logger.info("Number of individuals: "+ indNumber);
        Logger.info("Random seed for mutation operator selection: "+ mutationSeed);
        Logger.info("Random seed for individual selection: "+ individualSeed);
    }

    private void setup() {
        mutationRng = new JDKRandomBridge(RandomSource.MT, Long.valueOf(mutationSeed));
        individualRng = new JDKRandomBridge(RandomSource.MT, Long.valueOf(individualSeed));
        editTypes = Edit.parseEditClassesFromString(editType);
    }

    // Implementation of gin.util.Sampler's abstract method
    protected void sampleMethodsHook() {

        if ((indNumber < 1) || (genNumber < 1)) {
            Logger.info("Please enter a positive number of generations and individuals.");
        } else {

            writeNewHeader();
            
            if(homEnabled) {
            	RandomSampler randomSampler = new RandomSampler(this.projectDirectory,this.methodFile);
                randomSampler.methodData = this.methodData;
                randomSampler.patchNumber = this.patchNumber;
                randomSampler.classPath = this.classPath;
                randomSampler.editType= this.editType;
                Map<TargetMethod, List<UnitTestResultSet>> targetMap = randomSampler.sampleMethodsGP();
                
                Set<TargetMethod> targetMethods = targetMap.keySet();
                
                Logger.info(String.format("RandomSampler found %s methods", targetMethods.size()));
                
                for(TargetMethod method : targetMethods) {
                	List<UnitTestResultSet> unitTestResultSets = targetMap.get(method);
                	
                	Logger.info("Running HOM-GP on method " + method);

                    // Setup SourceFile for patching
                    SourceFile sourceFile = SourceFile.makeSourceFileForEditTypes(editTypes, method.getFileSource().getPath(), Collections.singletonList(method.getMethodName()));

                    search(method, new Patch(sourceFile), unitTestResultSets);
                }
            }
            
            else {
            	for (TargetMethod method : methodData) {

                    Logger.info("Running GP on method " + method);

                    // Setup SourceFile for patching
                    SourceFile sourceFile = SourceFile.makeSourceFileForEditTypes(editTypes, method.getFileSource().getPath(), Collections.singletonList(method.getMethodName()));

                    search(method, new Patch(sourceFile));

                }
            }
        }
    }

       /*============== Abstract methods  ==============*/

    // GP search strategy
    protected abstract void search(TargetMethod method, Patch origPatch);
    
    // GP HOM search strategy
    protected abstract void search(TargetMethod method, Patch origPatch, List<UnitTestResultSet> unitTestResultSets);

    // Individual selection
    protected abstract List<Patch> select(Map<Patch, Double> population, Patch origPatch, double origFitness);

    // Mutation operator
    protected abstract Patch mutate(Patch oldPatch);

    // Crossover operator
    protected abstract List<Patch> crossover(List<Patch> patches, Patch origPatch);

    // Whatever initialisation needs to be done for fitness calculations
    protected abstract UnitTestResultSet initFitness(String className, List<UnitTest> tests, Patch origPatch);

    // Calculate fitness
    protected abstract double fitness(UnitTestResultSet results);

    // Calculate fitness threshold, for selection to the next generation
    protected abstract boolean fitnessThreshold(UnitTestResultSet results, double originalFitness);

    // Compare two fitness values
    protected abstract double compareFitness(double newFitness, double oldFitness);

    /*============== Helper methods  ==============*/

    protected void writeNewHeader() {
        String[] entry = {"MethodName"
                        , "Patch"
                        , "Compiled"
                        , "AllTestsPassed"
                        , "TotalExecutionTime(ms)"
                        , "Fitness"
                        , "FitnessImprovement"
                        };
        try {
            outputFileWriter = new CSVWriter(new FileWriter(outputFile));
            outputFileWriter.writeNext(entry);
        } catch (IOException e) {
            Logger.error(e, "Exception writing results to the output file: " + outputFile.getAbsolutePath());
            Logger.trace(e);
            System.exit(-1);
        }
    }

    protected void writePatch(UnitTestResultSet results, String methodName, double fitness, double improvement) {
        String[] entry = {methodName
                        , results.getPatch().toString()
                        , Boolean.toString(results.getCleanCompile())
                        , Boolean.toString(results.allTestsSuccessful())
                        , Float.toString(results.totalExecutionTime() / 1000000.0f)
                        , Double.toString(fitness)
                        , Double.toString(improvement)
                        };
        outputFileWriter.writeNext(entry);
    }

}
