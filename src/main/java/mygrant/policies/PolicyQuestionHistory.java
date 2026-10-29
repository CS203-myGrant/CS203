package mygrant.policies;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.*;
import mygrant.user.UserRepository;

/** Persists only responses actually returned to this caller, never rejected raw model output. */
@Service
public class PolicyQuestionHistory {
    public record Entry(Long id,Long policyId,String question,JsonNode response,Instant createdAt){}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;
    public PolicyQuestionHistory(JdbcTemplate jdbc,ObjectMapper mapper,UserRepository users){this.jdbc=jdbc;this.mapper=mapper;this.users=users;}
    private Long owner(String email){return users.findByEmail(email).orElseThrow(()->new IllegalStateException("User not found")).getId();}
    private final String scope=" user_id = ? AND policy_id IS NOT DISTINCT FROM ? ";
    private RowMapper<Entry> rowMapper(){
        return (rs,row)->{
            try{return new Entry(rs.getLong("id"),rs.getObject("policy_id",Long.class),rs.getString("question"),
                    mapper.readTree(rs.getString("response_json")),rs.getTimestamp("created_at").toInstant());}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid saved question history",e);}
        };
    }
    public void record(String email,Long policyId,String question,Object response){
        try{jdbc.update("INSERT INTO policy_question_history (user_id,policy_id,question,response_json) VALUES (?,?,?,?)",
                owner(email),policyId,question,mapper.writeValueAsString(response));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Unable to save question history",e);}
    }
    public Page<Entry> list(String email,Long policyId,Pageable pageable){
        Long userId=owner(email);
        Long count=jdbc.queryForObject("SELECT count(*) FROM policy_question_history WHERE"+scope,Long.class,userId,policyId);
        var rows=jdbc.query("SELECT * FROM policy_question_history WHERE"+scope+"ORDER BY id ASC LIMIT ? OFFSET ?",
                rowMapper(),userId,policyId,pageable.getPageSize(),pageable.getOffset());
        return new PageImpl<>(rows,pageable,count==null?0:count);
    }
    public List<PolicyChatService.Turn> context(String email,Long policyId){
        var rows=jdbc.query("SELECT * FROM policy_question_history WHERE"+scope+"ORDER BY id DESC LIMIT 5",rowMapper(),owner(email),policyId);
        List<PolicyChatService.Turn> turns=new ArrayList<>();
        for(var row:rows.reversed()){
            turns.add(new PolicyChatService.Turn(PolicyChatService.Role.USER,row.question()));
            turns.add(new PolicyChatService.Turn(PolicyChatService.Role.ASSISTANT,row.response().path("answer").asText()));
        }
        return turns;
    }
}
