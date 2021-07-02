package gin.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import org.pmw.tinylog.Logger;

import gin.Patch;
import gin.edit.Edit;
import gin.test.UnitTest;
import gin.test.UnitTestResultSet;


/**
 * Method-based GPSimple search.
 * Includes: implementation of tournament selection, uniform crossover, and random mutation operator selection
 * Roughly based on: "A systematic study of automated program repair: Fixing 55 out of 105 bugs for $8 each." 
 * by Claire Le Goues, Michael Dewey-Vogt, Stephanie Forrest, Westley Weimer (ICSE 2012)
 * and its Java implementation at https://github.com/squaresLab/genprog4java 
 */

public abstract class GPSimple extends GP {
    
    public GPSimple(String[] args) {
        super(args);
    }   

    // Constructor used for testing
    public GPSimple(File projectDir, File methodFile) {
        super(projectDir, methodFile);
    }   

    // Percentage of population size to be selected during tournament selection
    private static double tournamentPercentage = 0.2;

    // Probability of adding an edit during uniform crossover
    private static double mutateProbability = 0.5;

    // Whatever initialisation needs to be done for fitness calculations
    @Override
    protected abstract UnitTestResultSet initFitness(String className, List<UnitTest> tests, Patch origPatch);

    // Calculate fitness
    @Override
    protected abstract double fitness(UnitTestResultSet results);

    // Calculate fitness threshold, for selection to the next generation
    @Override
    protected abstract boolean fitnessThreshold(UnitTestResultSet results, double orig);
    
     // Compare two fitness values, result of comparison > 0 if newFitness better than oldFitness
    @Override
    protected abstract double compareFitness(double newFitness, double oldFitness);

    /*============== Implementation of abstract methods  ==============*/

    /*====== Search ======*/

    // Simple GP search (based on Simple)
    protected void search(TargetMethod method, Patch origPatch) {

        String className = method.getClassName();
        String methodName = method.toString();
        List<UnitTest> tests = method.getGinTests();

        // Run original code
        UnitTestResultSet results = initFitness(className, tests, origPatch);

        // Calculate fitness and record result, including fitness improvement (currently 0)
        double orig = fitness(results);
        super.writePatch(results, methodName, orig, 0);

        // Keep best 
        double best = orig;

        // Generation 1
        Map<Patch, Double> population = new HashMap<>();
        population.put(origPatch, orig);

        for (int i = 1; i < indNumber; i++) {

            // Add a mutation
            Patch patch = mutate(origPatch);
            // If fitnessThreshold met, add it
            results = testPatch(className, tests, patch);
            if (fitnessThreshold(results, orig)) {
                population.put(patch, fitness(results));
            }

        }
        
        //create further generations
        for (int g = 0; g < genNumber; g++) {

            // Previous generation
            List<Patch> patches = new ArrayList(population.keySet());

            Logger.info("Creating generation: " + (g + 1));

            // Current generation
            Map<Patch, Double> newPopulation = new HashMap<>();

            // Select individuals for crossover
            List<Patch> selectedPatches = select(population, origPatch, orig);

            // Keep a list of patches after crossover
            List<Patch> crossoverPatches = crossover(selectedPatches, origPatch);

            // If less than indNumber variants produced, add random patches from the previous generation
            while (crossoverPatches.size() < indNumber) {
                crossoverPatches.add(patches.get(super.individualRng.nextInt(patches.size())).clone());
            }
            
            // Mutate the newly created population and check fitness
            for (Patch patch : crossoverPatches) {

                // Add a mutation
                patch = mutate(patch);

                Logger.debug("Testing patch: " + patch);

                // Test the patched source file
                results = testPatch(className, tests, patch);
                double newFitness = fitness(results);

                // If fitness threshold met, add patch to the mating population
                if (fitnessThreshold(results, orig)) {
                    newPopulation.put(patch, newFitness);
                }
                super.writePatch(results, methodName, newFitness, compareFitness(newFitness, orig));
            }

            population = new HashMap<Patch, Double>(newPopulation);
            if (population.isEmpty()) {
                population.put(origPatch, orig);
            }
            
        }

    }
    
    // HOM GP search (based on Simple GP Search)
    protected void search(TargetMethod method, Patch origPatch, List<UnitTestResultSet> unitTestResultSets) {

    	Logger.info(String.format("Found %s RandomSampler patches", unitTestResultSets.size()));
    	
        String className = method.getClassName();
        String methodName = method.toString();
        List<UnitTest> tests = method.getGinTests();
        
        
        // Run original code
        UnitTestResultSet results = initFitness(className, tests, origPatch);

        // Calculate fitness and record result, including fitness improvement (currently 0)
        double orig = fitness(results);
        super.writePatch(results, methodName, orig, 0);

        List<Patch> FOMPatches = new ArrayList<Patch>();
        for(int i=0;i<unitTestResultSets.size();++i) {
        	//TODO what if no result >=0?
        	if(unitTestResultSets.get(i).allTestsSuccessful()) {
        		FOMPatches.add(unitTestResultSets.get(i).getPatch());
        	}
        }
        Logger.info(String.format("Found %s FOM patches", FOMPatches.size()));
        
        Map<Patch, Double> population = new HashMap<>();
        //no FOM-Patches found -> GP with origPatch as start for mutation
        if(FOMPatches.size()==0) {
            population.put(origPatch, orig);

            for (int i = 1; i < indNumber; i++) {

                // Add a mutation
                Patch patch = mutate(origPatch);
                // If fitnessThreshold met, add it
                results = testPatch(className, tests, patch);
                if (fitnessThreshold(results, orig)) {
                    population.put(patch, fitness(results));
                }

            }
        }
        //single FOM-Patch found -> GP with single FOM Patch as start for mutation
        else if(FOMPatches.size()==1) {
            population.put(FOMPatches.get(0), fitness(testPatch(className, tests, FOMPatches.get(0))));

            for (int i = 1; i < indNumber; i++) {

                // Add a mutation
                Patch patch = mutate(origPatch);
                // If fitnessThreshold met, add it
                results = testPatch(className, tests, patch);
                if (fitnessThreshold(results, orig)) {
                    population.put(patch, fitness(testPatch(className, tests, patch)));
                }

            }
        }
        //multiple FOM-Patches found -> combine FOM-Patches randomly to form HOM-Patches for first generation
        else {
        	//TODO find stopping criterion in loop abort (e.g. timeout<1000)
        	/*for (int i=0, timeout=0; i < indNumber && timeout<(10*indNumber); ++timeout) {
        		List<Integer> editedLines = new ArrayList<Integer>();
        		List<Patch> patchesToCombine = new ArrayList<Patch>();
        		int j=0;
        		int timeoutLoop=0;
        		//TODO what if there are no distinctive Patches?	--> break after x times in loop?
        		while(j<2 && timeoutLoop<100) {
        			Patch FOMPatch = FOMPatches.get(super.individualRng.nextInt(FOMPatches.size()));
        			List<Integer> editedLinesPatch = FOMPatch.getEditedLines();
        			//only combine distinctive FOM patches (different edited lines)
        			if(!editedLinesPatch.containsAll(editedLines)) {
        				editedLines.addAll(editedLinesPatch);
        				patchesToCombine.add(FOMPatch);
        				++j;
        			}
        			++timeoutLoop;
        		}
        		*/
        	
        	for (int i=0; i < indNumber;) {
        		
        		List<Patch> patchesToCombine = new ArrayList<Patch>();
        		
        		//set number of FOM patches to combine for a HOM patch
        		int homBound = Math.min(FOMPatches.size(), homSize);
        		//combine random number (but at leat 2) random FOM Patches to a HOM Patch
        		for(int j=0;j<Math.max(super.individualRng.nextInt(homBound+1),2);++j) {
        			Patch FOMPatch = FOMPatches.get(super.individualRng.nextInt(FOMPatches.size()));
        			patchesToCombine.add(FOMPatch);
        		}
        		
        		Logger.info(String.format("Combining Patches. Length %s",patchesToCombine.size()));
        		
        		Patch HOMPatch;
        		if(patchesToCombine.size()==0) {
        			HOMPatch = origPatch;
        		}
        		else if(patchesToCombine.size()==1) {
        			HOMPatch = patchesToCombine.get(0);
        		}
        		else {
        			HOMPatch = combineFOMPatches(patchesToCombine);
        		}
        		if(fitnessThreshold(testPatch(className, tests, HOMPatch),orig)) {
        			population.put(HOMPatch, fitness(testPatch(className, tests, HOMPatch)));
        			++i;
        		}
        	}
        	Logger.info(String.format("Found %s HOM patches overall", population.keySet().size()));
        	
        }
        
        // Keep best 
        double best = orig;
        
        //create further generations
        for (int g = 0; g < genNumber; g++) {

            // Previous generation
            List<Patch> patches = new ArrayList(population.keySet());

            Logger.info("Creating generation: " + (g + 1));

            // Current generation
            Map<Patch, Double> newPopulation = new HashMap<>();

            // Select individuals for crossover
            List<Patch> selectedPatches = select(population, origPatch, orig);

            // Keep a list of patches after crossover
            List<Patch> crossoverPatches = crossover(selectedPatches, origPatch);

            // If less than indNumber variants produced, add random patches from the previous generation
            while (crossoverPatches.size() < indNumber) {
                crossoverPatches.add(patches.get(super.individualRng.nextInt(patches.size())).clone());
            }
            
            // Mutate the newly created population and check fitness
            for (Patch patch : crossoverPatches) {

                // Add a mutation
                //TODO errors because of mixed line/statement edits
            	patch = mutate(patch);

                Logger.debug("Testing patch: " + patch);

                // Test the patched source file
                results = testPatch(className, tests, patch);
                double newFitness = fitness(results);

                // If fitness threshold met, add patch to the mating population
                if (fitnessThreshold(results, orig)) {
                    newPopulation.put(patch, newFitness);
                }
                super.writePatch(results, methodName, newFitness, compareFitness(newFitness, orig));
            }

            population = new HashMap<Patch, Double>(newPopulation);
            if (population.isEmpty()) {
                population.put(origPatch, orig);
            }
            
        }

    }

    /*====== GP Operators ======*/

    // Adds a random edit of the given type with equal probability among allowed types
    protected Patch mutate(Patch oldPatch) {
        Patch patch = oldPatch.clone();
        patch.addRandomEditOfClasses(super.mutationRng, super.editTypes);
        return patch;
    }

    // Tournament selection for patches
    protected List<Patch> select(Map<Patch, Double> population, Patch origPatch, double origFitness) {

        List<Patch> patches = new ArrayList(population.keySet());
        if (patches.size() < super.indNumber) {
            population.put(origPatch, origFitness);
            while (patches.size() < super.indNumber) {
                patches.add(origPatch);
            }
        }
        List<Patch> selectedPatches = new ArrayList<>();

        // Pick half of the population size
        for (int i = 0; i < super.indNumber / 2; i++) {

            Collections.shuffle(patches, super.individualRng);

            // Best patch from x% randomly selected patches picked each time
            Patch bestPatch = patches.get(0);
            double best = population.get(bestPatch);
            for (int j = 1; j < (super.indNumber * tournamentPercentage); j++) {
                Patch patch = patches.get(j);
                double fitness = population.get(patch);

                if (compareFitness(fitness, best) > 0) {
                    bestPatch = patch;
                    best = fitness;
                }
            }

            selectedPatches.add(bestPatch.clone());

        }
        return selectedPatches;
    }

    // Uniform crossover: patch1patch2 and patch2patch1 created, each edit added with x% probability
    protected  List<Patch> crossover(List<Patch> patches, Patch origPatch) {

        List<Patch> crossedPatches = new ArrayList<>();

        Collections.shuffle(patches, super.individualRng);
        int half = patches.size() / 2;
        for (int i = 0; i < half; i++) {

            Patch parent1 = patches.get(i);
            Patch parent2 = patches.get(i + half);
            List<Edit> list1 = parent1.getEdits();
            List<Edit> list2 = parent2.getEdits();

            Patch child1 = origPatch.clone();
            Patch child2 = origPatch.clone();

            for (int j = 0; j < list1.size(); j++) {
                if (super.mutationRng.nextFloat() > mutateProbability) {
                    child1.add(list1.get(j));
                }
            }
            for (int j = 0; j < list2.size(); j++) {
                if (super.mutationRng.nextFloat() > mutateProbability) {
                    child1.add(list2.get(j));
                }
                if (super.mutationRng.nextFloat() > mutateProbability) {
                    child2.add(list2.get(j));
                }
            }
            for (int j = 0; j < list1.size(); j++) {
                if (super.mutationRng.nextFloat() > mutateProbability) {
                    child2.add(list1.get(j));
                }
            }

            crossedPatches.add(parent1);
            crossedPatches.add(parent2);
            crossedPatches.add(child1);
            crossedPatches.add(child2);
        }

        return crossedPatches;
    }
    //combining multiple Patches for creating HOM-Patches
    protected Patch combineFOMPatches(List<Patch> patches) {
    	Patch newPatch = patches.get(0).clone();
    	for(int i=1;i<patches.size();++i) {
    		List<Edit> edits = patches.get(i).getEdits();
    		for(int j=0;j<edits.size();++j) {
    			Edit edit = edits.get(j);
    			if(newPatch.getEdits().contains(j))	continue;
    			else	newPatch.add(edit);
    		}
    	}
    	return newPatch;
    }

}

