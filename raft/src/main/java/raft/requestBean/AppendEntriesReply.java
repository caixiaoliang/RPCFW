package raft.requestBean;

import java.util.List;

public class AppendEntriesReply {
    private int Term;
    private  boolean Success;

    public int getTerm() {
        return Term;
    }

    public void setTerm(int term) {
        Term = term;
    }

    public boolean isSuccess() {
        return Success;
    }

    public void setSuccess(boolean success) {
        Success = success;
    }
}