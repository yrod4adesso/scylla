package de.hpi.bpt.scylla.plugin.batch;

import java.util.List;

import co.paralleluniverse.fibers.SuspendExecution;
import de.hpi.bpt.scylla.logger.DebugLogger;
import de.hpi.bpt.scylla.model.process.ProcessModel;
import de.hpi.bpt.scylla.model.process.graph.exception.MultipleStartNodesException;
import de.hpi.bpt.scylla.model.process.graph.exception.NoStartNodeException;
import de.hpi.bpt.scylla.model.process.graph.exception.NodeNotFoundException;
import de.hpi.bpt.scylla.simulation.ProcessInstance;
import de.hpi.bpt.scylla.simulation.ProcessSimulationComponents;
import de.hpi.bpt.scylla.simulation.SimulationModel;
import de.hpi.bpt.scylla.simulation.event.BPMNStartEvent;
import de.hpi.bpt.scylla.simulation.event.ScyllaEvent;
import de.hpi.bpt.scylla.simulation.event.TaskBeginEvent;
import de.hpi.bpt.scylla.simulation.utils.SimulationUtils;
import desmoj.core.simulator.Event;
import desmoj.core.simulator.Model;
import desmoj.core.simulator.TimeInstant;

public class BatchClusterStartEvent extends Event<BatchCluster> {

    public BatchClusterStartEvent(Model owner, String name, boolean showInTrace) {
        super(owner, "BCStart_" + name, showInTrace);
    }

    //TODO think of moving this stuff to batch cluster class
    @Override
    public void eventRoutine(BatchCluster cluster) throws SuspendExecution {

        BatchActivity activity = cluster.getBatchActivity();
        int nodeId = activity.getNodeId();

        List<TaskBeginEvent> parentalStartEvents = cluster.getParentalStartEvents();


        // Schedule all task begin events of the process instance
        // This does not schedule the activities inside the subprocess
        for (TaskBeginEvent pse : parentalStartEvents) {
            pse.schedule();
        }
        // schedule subprocess start events for all process instances in parent
        // processInstances and parentalStartEvents are ordered the same way

        // Set the responsible process instance in the batch cluster, first one by default
        cluster.setResponsibleProcessInstance(parentalStartEvents.get(0).getProcessInstance());

        // Go through all process instances. If it's the first one, schedule it. If not, save it to be scheduled later on
        for (int j = 0; j < parentalStartEvents.size(); j++) {
            TaskBeginEvent startEvent = parentalStartEvents.get(j);
            ProcessInstance responsibleProcessInstance = startEvent.getProcessInstance();
            
            Integer nodeIdToSchedule = nodeId;
            ScyllaEvent startEventToSchedule = startEvent;
            ProcessInstance processInstanceToSchedule = responsibleProcessInstance;
            
            if(!cluster.isBatchTask()) {
            	SimulationModel model = (SimulationModel) responsibleProcessInstance.getModel();
            	String source = startEvent.getSource();
            	TimeInstant currentSimulationTime = cluster.presentTime();

            	ProcessSimulationComponents pSimComponentsOfSubprocess = cluster.getProcessSimulationComponents().getChildren().get(nodeId);
            	int processInstanceId = responsibleProcessInstance.getId();
                boolean showInTrace = responsibleProcessInstance.traceIsOn();
                ProcessModel subprocess = pSimComponentsOfSubprocess.getProcessModel();
                Integer startNodeId;
                try {
                    startNodeId = subprocess.getStartNode();
                } catch (NodeNotFoundException | MultipleStartNodesException | NoStartNodeException e) {
                    DebugLogger.error("Start node of process model " + subprocess.getId() + " not found.");
                    e.printStackTrace();
                    SimulationUtils.abort(model, responsibleProcessInstance, nodeId, traceIsOn());
                    return;
                }
                ProcessInstance subprocessInstance = new ProcessInstance(model, subprocess, processInstanceId, showInTrace);
                subprocessInstance.setParent(responsibleProcessInstance);

                ScyllaEvent subprocessEvent = new BPMNStartEvent(model, source, currentSimulationTime,
                        pSimComponentsOfSubprocess, subprocessInstance, startNodeId);
                
                nodeIdToSchedule = startNodeId;
                startEventToSchedule = subprocessEvent;
                processInstanceToSchedule = subprocessInstance;
           
            } else {
            	startEvent.cancel();
            }
            
            if (j == 0) { // If it is the first process instance, schedule it...
            	startEventToSchedule.schedule(processInstanceToSchedule);
                cluster.setStartNodeId(nodeIdToSchedule);
            } else { // ...if not, save them for later
            	cluster.queueEvent(nodeIdToSchedule, startEventToSchedule);
            }


        }
        // move batch cluster from list of not started ones to running ones
        BatchPluginUtils pluginInstance = BatchPluginUtils.getInstance();
        pluginInstance.setClusterToRunning(cluster);

        // next node and timespan to next event determined by responsible process instance
        // tasks resources only assigned to responsible subprocess instance

        // only responsible subprocess instance is simulated
        // other subprocess instances of batch are covered in process logs
    }

}
