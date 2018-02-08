package beam.analysis.physsim;

import org.junit.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PhyssimCalcLinkStatsTest{

    private static String BASE_PATH = new File("").getAbsolutePath();;
    private static String OUTPUT_DIR_PATH = BASE_PATH+"/test/input/equil-square/test-data/output";
    private static String EVENTS_FILE_PATH = BASE_PATH+"/test/input/equil-square/test-data/physSimEvents.relative-speeds.xml";
    private static String NETWORK_FILE_PATH = BASE_PATH+"/test/input/equil-square/test-data/physSimNetwork.relative-speeds.xml";
    private static PhyssimCalcLinkStats physsimCalcLinkStats;

    static {
        createDummySimWithXML();
    }

    private synchronized static void createDummySimWithXML(){

        Config config = ConfigUtils.createConfig();
        OutputDirectoryHierarchy.OverwriteFileSetting overwriteExistingFiles = OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles;
        OutputDirectoryHierarchy outputDirectoryHierarchy = new OutputDirectoryHierarchy(OUTPUT_DIR_PATH, overwriteExistingFiles);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        MatsimNetworkReader matsimNetworkReader= new MatsimNetworkReader(network);
        matsimNetworkReader.readFile(NETWORK_FILE_PATH);

        TravelTimeCalculatorConfigGroup defaultTravelTimeCalculator= config.travelTimeCalculator();
        TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(network, defaultTravelTimeCalculator);

        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(travelTimeCalculator);

        physsimCalcLinkStats = new PhyssimCalcLinkStats(network, outputDirectoryHierarchy);

        physsimCalcLinkStats.notifyIterationStarts(eventsManager);

        MatsimEventsReader matsimEventsReader = new MatsimEventsReader(eventsManager);
        matsimEventsReader.readFile(EVENTS_FILE_PATH);
        physsimCalcLinkStats.notifyIterationEnds(0, travelTimeCalculator);
    }


    @Test
    public void testShouldPassShouldReturnCountRelativeSpeedOfSpecificHour(){
        Double expectedResult=10.0;
        Double actualResult =  physsimCalcLinkStats.getRelativeSpeedOfSpecificHour(0,7);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnCountOfAllRelativeSpeedCategoryForSpecificHour(){
        List<Double> relativeSpeedCategoryList=physsimCalcLinkStats.getSortedListRelativeSpeedCategoryList();
        Double expectedResult=260.0;
        Double actualRelativeSpeedSum = 0.0;
        for(Double category:relativeSpeedCategoryList){
            actualRelativeSpeedSum = actualRelativeSpeedSum + physsimCalcLinkStats.getRelativeSpeedOfSpecificHour(category.intValue(),7);
        }
        assertEquals(expectedResult, actualRelativeSpeedSum);
    }
    @Test
    public void testShouldPassShouldReturnSumOfRelativeSpeedForSpecificHour(){
        Double expectedResult=148.0;
        Double actualResult=physsimCalcLinkStats.getRelativeSpeedCountOfSpecificCategory(0);
        assertEquals(expectedResult, actualResult);
    }

}
