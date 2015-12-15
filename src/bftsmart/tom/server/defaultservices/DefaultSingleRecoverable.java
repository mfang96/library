/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.tom.server.defaultservices;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.Arrays;

import bftsmart.reconfiguration.util.TOMConfiguration;
import bftsmart.statemanagement.ApplicationState;
import bftsmart.statemanagement.StateManager;
import bftsmart.statemanagement.strategy.StandardStateManager;
import bftsmart.tom.MessageContext;
import bftsmart.tom.ReplicaContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.server.Recoverable;
import bftsmart.tom.server.SingleExecutable;
import bftsmart.tom.util.Logger;

/**
 *
 * @author Marcel Santos
 */
public abstract class DefaultSingleRecoverable implements Recoverable, SingleExecutable {
    
	protected ReplicaContext replicaContext;
    private TOMConfiguration config;
	private int checkpointPeriod;

    private ReentrantLock logLock = new ReentrantLock();
    private ReentrantLock hashLock = new ReentrantLock();
    private ReentrantLock stateLock = new ReentrantLock();
    
    private MessageDigest md;
        
    private StateLog log;
    private List<byte[]> commands = new ArrayList<>();
    private List<MessageContext> msgContexts = new ArrayList<>();
    
    private StateManager stateManager;
    
    public DefaultSingleRecoverable() {

        try {
            md = MessageDigest.getInstance("MD5"); // TODO: shouldn't it be SHA?
        } catch (NoSuchAlgorithmException ex) {
            java.util.logging.Logger.getLogger(DefaultSingleRecoverable.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    @Override
    public byte[] executeOrdered(byte[] command, MessageContext msgCtx) {
        
        return executeOrdered(command, msgCtx, false);
        
    }
    
    private byte[] executeOrdered(byte[] command, MessageContext msgCtx, boolean noop) {
        
        int eid = msgCtx.getConsensusId();
        
        byte[] reply = null;
            
        if (!noop) {
            stateLock.lock();
            reply = appExecuteOrdered(command, msgCtx);
            stateLock.unlock();
        }
        
        commands.add(command);
        msgContexts.add(msgCtx);
        
        if(msgCtx.isLastInBatch()) {
	        if ((eid > 0) && ((eid % checkpointPeriod) == 0)) {
	            Logger.println("(DefaultSingleRecoverable.executeOrdered) Performing checkpoint for consensus " + eid);
	            stateLock.lock();
	            byte[] snapshot = getSnapshot();
	            stateLock.unlock();
	            saveState(snapshot, eid);
	        } else {
	            saveCommands(commands.toArray(new byte[0][]), msgContexts.toArray(new MessageContext[0]));
	        }
			getStateManager().setLastEID(eid);
	        commands = new ArrayList<>();
                msgContexts = new ArrayList<>();
        }
        return reply;
    }
    
    public final byte[] computeHash(byte[] data) {
        byte[] ret = null;
        hashLock.lock();
        ret = md.digest(data);
        hashLock.unlock();

        return ret;
    }
    
    private StateLog getLog() {
        if(log == null)
           	initLog();
    	return log;
    }
    
    private void saveState(byte[] snapshot, int lastEid) {
        StateLog thisLog = getLog();

        logLock.lock();

        Logger.println("(TOMLayer.saveState) Saving state of EID " + lastEid);

        thisLog.newCheckpoint(snapshot, computeHash(snapshot), lastEid);
        thisLog.setLastEid(-1);
        thisLog.setLastCheckpointEid(lastEid);

        logLock.unlock();
        /*System.out.println("fiz checkpoint");
        System.out.println("tamanho do snapshot: " + snapshot.length);
        System.out.println("tamanho do log: " + thisLog.getMessageBatches().length);*/
        Logger.println("(TOMLayer.saveState) Finished saving state of EID " + lastEid);
    }

    private void saveCommands(byte[][] commands, MessageContext[] msgCtx) {
        
        if (commands.length != msgCtx.length) {
            System.out.println("----SIZE OF COMMANDS AND MESSAGE CONTEXTS IS DIFFERENT----");
            System.out.println("----COMMANDS: " + commands.length + ", CONTEXTS: " + msgCtx.length + " ----");
        }
        logLock.lock();

        int eid = msgCtx[0].getConsensusId();
        int batchStart = 0;
        for (int i = 0; i <= msgCtx.length; i++) {
            if (i == msgCtx.length) { // the batch command contains only one command or it is the last position of the array
                byte[][] batch = Arrays.copyOfRange(commands, batchStart, i);
                MessageContext[] batchMsgCtx = Arrays.copyOfRange(msgCtx, batchStart, i);
                log.addMessageBatch(batch, batchMsgCtx, eid);
            } else {
                if (msgCtx[i].getConsensusId() > eid) { // saves commands when the eid changes or when it is the last batch
                    byte[][] batch = Arrays.copyOfRange(commands, batchStart, i);
                    MessageContext[] batchMsgCtx = Arrays.copyOfRange(msgCtx, batchStart, i);
                    log.addMessageBatch(batch, batchMsgCtx, eid);
                    eid = msgCtx[i].getConsensusId();
                    batchStart = i;
                }
            }
        }
        
        logLock.unlock();
    }

    @Override
    public ApplicationState getState(int eid, boolean sendState) {
        logLock.lock();
        ApplicationState ret = (eid > -1 ? getLog().getApplicationState(eid, sendState) : new DefaultApplicationState());

        // Only will send a state if I have a proof for the last logged decision/consensus
        //TODO: I should always make sure to have a log with proofs, since this is a result
        // of not storing anything after a checkpoint and before logging more requests        
        if (ret.getLastProof() == null) ret = new DefaultApplicationState();

        System.out.println("Getting log until eid " + eid + ", null: " + (ret == null));
        logLock.unlock();
        return ret;
    }
    
    @Override
    public int setState(ApplicationState recvState) {
        int lastEid = -1;
        if (recvState instanceof DefaultApplicationState) {
            
            DefaultApplicationState state = (DefaultApplicationState) recvState;
            
            System.out.println("(DefaultSingleRecoverable.setState) last eid in state: " + state.getLastEid());
            
            logLock.lock();
            if(log == null)
            	initLog();
            log.update(state);
            logLock.unlock();
            
            int lastCheckpointEid = state.getLastCheckpointEid();
            
            lastEid = state.getLastEid();

            bftsmart.tom.util.Logger.println("(DefaultSingleRecoverable.setState) I'm going to update myself from EID "
                    + lastCheckpointEid + " to EID " + lastEid);

            stateLock.lock();
            installSnapshot(state.getState());

            for (int eid = lastCheckpointEid + 1; eid <= lastEid; eid++) {
                try {
                    bftsmart.tom.util.Logger.println("(DurabilityCoordinator.setState) interpreting and verifying batched requests for eid " + eid);

                    CommandsInfo cmdInfo = state.getMessageBatch(eid); 
                    byte[][] cmds = cmdInfo.commands; // take a batch
                    MessageContext[] msgCtxs = cmdInfo.msgCtx;
                    
                    if (cmds == null || msgCtxs == null || msgCtxs[0].isNoOp()) {
                        continue;
                    }
                    
                    for(int i = 0; i < cmds.length; i++) {
                    	appExecuteOrdered(cmds[i], msgCtxs[i]);
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                    if (e instanceof ArrayIndexOutOfBoundsException) {
                        System.out.println("Eid do ultimo checkpoint: " + state.getLastCheckpointEid());
                        System.out.println("Eid do ultimo consenso: " + state.getLastEid());
                        System.out.println("numero de mensagens supostamente no batch: " + (state.getLastEid() - state.getLastCheckpointEid() + 1));
                        System.out.println("numero de mensagens realmente no batch: " + state.getMessageBatches().length);
                    }
                }
            }
            stateLock.unlock();

        }

        return lastEid;
    }

        @Override
        public void setReplicaContext(ReplicaContext replicaContext) {
            this.replicaContext = replicaContext;
            this.config = replicaContext.getStaticConfiguration();
            if (log == null) {
                checkpointPeriod = config.getCheckpointPeriod();
                byte[] state = getSnapshot();
                if (config.isToLog() && config.logToDisk()) {
                    int replicaId = config.getProcessId();
                    boolean isToLog = config.isToLog();
                    boolean syncLog = config.isToWriteSyncLog();
                    boolean syncCkp = config.isToWriteSyncCkp();
                    log = new DiskStateLog(replicaId, state, computeHash(state), isToLog, syncLog, syncCkp);

                    ApplicationState storedState = ((DiskStateLog) log).loadDurableState();
                    if (storedState.getLastEid() > 0) {
                        setState(storedState);
                        getStateManager().setLastEID(storedState.getLastEid());
                    }
                } else {
                    log = new StateLog(checkpointPeriod, state, computeHash(state));
                }
            }
            getStateManager().askCurrentConsensusId();
        }
	/*@Override
	public void setReplicaContext(ReplicaContext replicaCtx) {
		this.replicaContext = replicaCtx;
    	this.config = replicaCtx.getStaticConfiguration();
	}*/

	@Override
    public StateManager getStateManager() {
    	if(stateManager == null)
    		stateManager = new StandardStateManager();
    	return stateManager;
    }
	
	protected void initLog() {
    	if(log == null) {
    		checkpointPeriod = config.getCheckpointPeriod();
            byte[] state = getSnapshot();
            if(config.isToLog() && config.logToDisk()) {
            	int replicaId = config.getProcessId();
            	boolean isToLog = config.isToLog();
            	boolean syncLog = config.isToWriteSyncLog();
            	boolean syncCkp = config.isToWriteSyncCkp();
            	log = new DiskStateLog(replicaId, state, computeHash(state), isToLog, syncLog, syncCkp);
            } else
            	log = new StateLog(checkpointPeriod, state, computeHash(state));
    	}
	}
    
        
        
    @Override
    public byte[] executeUnordered(byte[] command, MessageContext msgCtx) {
        return appExecuteUnordered(command, msgCtx);
    }
    
    @Override
    public void Op(int CID, TOMMessage[] requests, MessageContext msgCtx) {
        //The messages are logged within 'executeOrdered(...)' instead of in this method.
    }

    @Override
    public void noOp(int CID, MessageContext msgCtx) {
         
        executeOrdered(new byte[0], msgCtx, true);
    }
    
    public abstract void installSnapshot(byte[] state);
    
    public abstract byte[] getSnapshot();
    
    public abstract byte[] appExecuteOrdered(byte[] command, MessageContext msgCtx);
    
    public abstract byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx);
}
