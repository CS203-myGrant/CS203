package mygrant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import mygrant.ai.OpenAiGenerationService;
import mygrant.auth.JwtService;
import mygrant.impact.*;
import mygrant.policies.*;
import mygrant.user.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PolicyQaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;
    @Autowired JwtService jwt;
    @MockBean VectorStore vectors;
    @MockBean OpenAiGenerationService generator;
    @MockBean ImpactJudgeService judge;

    String account(String visa){
        var user=new User("QA test","qa-"+UUID.randomUUID()+"@example.org","unused",visa,null,null);
        users.saveAndFlush(user);
        return "Bearer "+jwt.generateAccessToken(user);
    }

    @Test void generalQaIgnoresSpoofedVisaAndPersistsOnlyCallersHistory() throws Exception {
        var document=Document.builder().text("A rule for F-1 students").metadata(Map.of("visaType","F-1","policyId","42",
                "externalId","f1-rule","title","F-1 rule","agency","DHS","status","FINAL","sourceType","PRIMARY",
                "publicationDate","2026-01-01","sourceUrl","https://example.org/f1")).build();
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        when(generator.generate(anyString(),anyString())).thenReturn("A general policy answer [1].");
        when(judge.evaluateAnswer(anyString(),anyString())).thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.YES,"Supported","judge"));
        String owner=account("F-1"),other=account("H-1B");
        mvc.perform(post("/api/v1/rag/query").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"What changed?\",\"visaType\":\"H-1B\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.citations[0].externalId").value("f1-rule"));
        mvc.perform(get("/api/v1/rag/history").header("Authorization",owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].response.answer").value("A general policy answer [1]."));
        mvc.perform(get("/api/v1/rag/history").header("Authorization",other)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void persistsAnswersAndIsolatesHistoryAcrossUsersAndProfiles() throws Exception {
        var policy=policies.saveAndFlush(new PolicyDocument("qa-"+UUID.randomUUID(),"Student policy","DHS","F-1",PolicyStatus.FINAL,
                SourceType.PRIMARY,LocalDate.of(2026,1,1),null,"https://example.org/policy","SUMMARY: Student policy details.","hash"));
        var document=Document.builder().text("Student policy details.").metadata(Map.of("visaType","F-1","policyId",policy.getId().toString())).build();
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        when(generator.generateJson(anyString(),anyString(),anyMap(),eq("policy_follow_up")))
                .thenReturn("{\"answer\":\"A saved cited answer [1].\",\"requiresReview\":false}");
        when(judge.evaluateAnswer(anyString(),anyString())).thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.YES,"Supported","judge"));
        String owner=account("F-1"),stranger=account("H-1B");
        String path="/api/v1/policies/"+policy.getId()+"/questions";
        mvc.perform(post(path).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"My saved question\",\"history\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.citations[0].sourceUrl").value("https://example.org/policy"))
                .andExpect(jsonPath("$.confidence").value("LOW"));
        mvc.perform(get(path).header("Authorization",owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].response.answer").value("A saved cited answer [1]."));
        mvc.perform(get(path).header("Authorization",stranger)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post(path).header("Authorization",stranger).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Does it apply?\",\"history\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.citations").isEmpty());
        verify(generator,times(1)).generateJson(anyString(),anyString(),anyMap(),eq("policy_follow_up"));
        mvc.perform(get(path).header("Authorization",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
}
