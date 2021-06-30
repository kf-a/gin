package gin.hom;

import com.sampullara.cli.Argument;
import com.sampullara.cli.Args;
import gin.LocalSearch;
import gin.test.UnitTest;
import org.pmw.tinylog.Logger;

import gin.util.Profiler;

import java.io.File;
import java.util.List;
import java.util.Set;

public class ProfilerSensitivity {

    // Commandline arguments
    @Argument(alias = "p", description = "Project name, required", required = true)
    protected String projectName;

    @Argument(alias = "d", description = "Project Directory, required", required = true)
    protected File projectDir;



    private Profiler profiler;

    //Number of Methods to be improved
    //todo: wie legen wie dies am besten fest?
    private int targetMethodNumber = 10;


    ProfilerSensitivity (String[] args){
        Args.parseOrExit(this, args);
        this.profiler = new Profiler(args);

        //deactivate profiler-output to csv file
        profiler.dontWrite = true;


    }

    public static void main(String[] args){

        ProfilerSensitivity ps = new ProfilerSensitivity(args);
        ps.profileSensitivity();
    }

	public void profileSensitivity() {
		Logger.info(String.format("You are using genetic improvement with higher order mutants and" +
                " test suite profiling for the Project: %s ",this.projectName));
		//Start the profiler
        Logger.info("Starting the Profiler. This may take a long time, if the test suite is large");
        this.profiler.profile();

        //Get Results of the Profiler
        Logger.info("Profiler finished");
        List<Profiler.HotMethod> hotMethods = profiler.get_hotMethods();

        //Iterate through the best hotMethods, hotMethods is already sorted
        int iteratorMax = Math.min(this.targetMethodNumber, hotMethods.size());
        Logger.info(String.format("Starting Improvement with %d hottest methods",iteratorMax));
        for (int i = 0; i<iteratorMax; i++){
            //build Input for LocalSearch
            Profiler.HotMethod method = hotMethods.get(i);
            String methodname = trimMethodName(method);
            String file = findFile(method);

            String[] lsArgs = new String[3];
            lsArgs[0] = "-f ".concat(file);
            lsArgs[1] = "-m ".concat(methodname);
            lsArgs[2] = "-hom true";

            //run LocalSearch
            Logger.info(String.format("Improving Method %s in File %s", lsArgs[1], lsArgs[0]));
            //TODO parsing args in LocalSearch-constructor not working
            LocalSearch ls = new LocalSearch(lsArgs);
            ls.search();
            //todo: collect results
        }
	}

    //todo: check correctness of this
    private String findFile(Profiler.HotMethod method){
        String name = method.getName();
        int p=name.lastIndexOf(".");
        String filename=name.substring(0,p);
        filename = filename.replace(".",File.separator);
        filename = "src"+File.separator+"main"+File.separator+"java"+File.separator+filename+".java";
        return filename;
    }
    private String trimMethodName(Profiler.HotMethod method){
        String name = method.getName();
        int p=name.lastIndexOf(".");
        String methodname=name.substring(p+1,name.length()-1);
        return methodname;
    }
}
