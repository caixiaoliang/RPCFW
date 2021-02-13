package raft.requestBean;

import java.util.List;

public class AppendEntriesArgs {
    private int Term;
    private String LeaderId;
    private List<Entry> Entries;


    private  AppendEntriesArgs(int Term, String LeaderId, List<Entry> entries){
        this.Term=Term;
        this.LeaderId=LeaderId;
        this.Entries = entries;
    }

    private  AppendEntriesArgs(Builder b){
        this(b.Term,b.LeaderId,b.Entries);
    }

    public static Builder newBuilder(){
        return new Builder();
    }

    public static class Builder{
        private int Term;
        private String LeaderId;
        private List<Entry> Entries;

        public Builder setTerm(int Term){
            this.Term =Term;
            return this;
        }

        public Builder setLeaderId(String LeaderId){
            this.LeaderId= LeaderId;
            return this;
        }

        public Builder setEntries(List<Entry> entries){
            this.Entries = entries;
            return this;
        }

        public AppendEntriesArgs build(){
            return  new AppendEntriesArgs(this);
        }

    }
}