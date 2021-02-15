package raft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raft.common.Daemon;
import raft.common.RaftPeer;
import raft.common.id.RaftPeerId;
import raft.requestBean.RequestVoteArgs;
import raft.requestBean.RequestVoteReply;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LeaderElection extends Daemon {
    public static final Logger LOG = LoggerFactory.getLogger(LeaderElection.class);

    int REQUEST_VOTE_RPC_TIMEOUT =300;


    private ServerState serverState;
    private RaftServerImpl server;
    private ExecutorService executorService;
    private ExecutorCompletionService<RequestVoteReply> completionService;
    private volatile boolean running;
    private Collection<RaftPeer> others;

    LeaderElection(RaftServerImpl server) {
        this.server = server;
        this.serverState = server.getServerState();
        this.running = true;
        this.others=serverState.getOtherPeers();
        initExecutor();
    }

    private void initExecutor(){
        this.executorService = Executors.newFixedThreadPool(100);
        this.completionService = new ExecutorCompletionService<>(executorService);
    }

    @Override
    public void run() {
        canvassVotes();
    }

    public void stopRunning() {
        this.running = false;
    }


    //选举开始前给每个server 初始化一个 client，初始化client 费时，导致选举超时
    public void canvassVotes() {
        //不断选举家直到结束，此时选举超时daemon 在转变为 candidate时已经关闭，无需等待超时后再开始选举
        while (running && server.isCandidate()) {
            try {
                Thread.sleep(server.getRandomTimeOutMs());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            serverState.initEleciton();
            LOG.info("server:[{}] term:[{}] candidate start canvassVotes", serverState.getSelfId(), serverState.getCurrentTerm());
            AtomicInteger receivedVotesCnt = new AtomicInteger(1);
            int peersLen = serverState.getPeersCount();

            for (RaftPeer p : serverState.getPeers()) {
                if (!running) {
                    return;
                }
                if (p.getId().toString() != serverState.getSelfId().toString()) {
                    RequestVoteArgs args = server.createRequestVoteRequest(serverState.getCurrentTerm(), serverState.getSelfId().toString());
                    //LOG.info("server:[{}] canvassVotes RequestVoteArgs term:{} peer_id:{}", serverState.getSelfId(), args.getTerm(), p.getId());
                    CompletableFuture.supplyAsync(() -> server.getServrRpc().sendRequestVote(p.getId(), args), executorService)
                            .thenAccept(reply -> {
                                LOG.info("canvassVotes server:{} get requestVote reply term:{} voted:{}", serverState.getSelfId(),
                                        reply.getTerm(), reply.isVoteGranted());

                                if (server.isCandidate()) {
                                    if (reply.getTerm() > serverState.getCurrentTerm()) {
                                        server.changeToFollower(reply.getTerm());
                                        LOG.info("server:{} will change to follower in canvassVotes replyid:{} reply term:{} voted:{}", serverState.getSelfId(),
                                                reply.getReplyId(),reply.getTerm(),reply.isVoteGranted());
                                        return;
                                    }
                                    if (reply.isVoteGranted()) {
                                        if (receivedVotesCnt.get() >= peersLen / 2) {
                                            server.changeToLeader();
                                            return;
                                        }
                                        receivedVotesCnt.addAndGet(1);
                                    }
                                }
                            });
                }
            }
        }
    }

    enum Result{PASSED, REJECTED,TIMEOUT,NEWTERM,EXCEPTION}
    private static class ResultAndTerm{
        final Result result;
        final int term;
        ResultAndTerm(Result r, int t){
            result=r;
            term = t;
        }
    }

    public void canvassVotes1(){
        while (running && server.isCandidate()) {
            int electionTerm = serverState.initEleciton();
            int submittedCount= submitRequest(electionTerm);
            ResultAndTerm r = WaitForResult(submittedCount,electionTerm);
            switch (r.result){
                case PASSED:
                    server.changeToLeader();
                case NEWTERM:
                    server.changeToFollower(r.term);
                case REJECTED:
                case TIMEOUT:
                case EXCEPTION:
            }
        }
    }

    private int submitRequest(int electionTerm){
        AtomicInteger submitted= new AtomicInteger();
        others.forEach((peer)->{
            RequestVoteArgs args = server.createRequestVoteRequest(electionTerm, serverState.getSelfId().toString());
            completionService.submit(()->server.getServrRpc().sendRequestVote(peer.getId(), args));
            submitted.getAndIncrement();
        });
        return submitted.get();
    }

    //TODO add time out for requestVote time out
    private ResultAndTerm WaitForResult(int submittedCount,int electionTerm){
        int receivedVotesCnt = 1;
        int waitForNum = submittedCount;
        List<RaftPeerId> votedPeers = new ArrayList<>();
        //TODO rpc 超时和总的选举超时
        RaftTimer elapsedTime = new RaftTimer();
        //请求选举超时随机为一次选举超时
        int waitTime = server.getRandomTimeOutMs();
        while (waitForNum>0 && running&& server.isCandidate()){
            try {
                if(elapsedTime.getElapsedTime()>waitTime){ return new ResultAndTerm(Result.TIMEOUT,-1); }
                Future< RequestVoteReply> future = completionService.poll(REQUEST_VOTE_RPC_TIMEOUT, TimeUnit.MILLISECONDS);
                //返回请求超时
                if(future==null){ continue; }

                RequestVoteReply reply = future.get();
                if (reply.getTerm() > electionTerm) { return new ResultAndTerm(Result.NEWTERM,reply.getTerm()); }

                if (reply.isVoteGranted()) {
                    votedPeers.add(RaftPeerId.valueOf(reply.getReplyId()));
                    LOG.info("vote granted ..............");
                    if (votedPeers.size() >= serverState.getPeersCount()/ 2) { return new ResultAndTerm(Result.PASSED,reply.getTerm()); }
                }
                waitForNum--;

            } catch (InterruptedException | ExecutionException e ) {
                return new ResultAndTerm(Result.EXCEPTION,-1);
            }
        }

        return new ResultAndTerm(Result.REJECTED,-1);
    }


}
