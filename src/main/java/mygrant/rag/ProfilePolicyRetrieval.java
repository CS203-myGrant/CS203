package mygrant.rag;

import java.util.List;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import mygrant.user.*;

/** Server-owned profile filters apply before vector retrieval and are checked again afterwards. */
@Service
public class ProfilePolicyRetrieval {
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final VectorStore vectors;
    public ProfilePolicyRetrieval(UserRepository users,UserProfileRepository profiles,VectorStore vectors){
        this.users=users;this.profiles=profiles;this.vectors=vectors;
    }
    public UserProfile profile(String email){
        var user=users.findByEmail(email).orElseThrow(()->new IllegalStateException("User not found"));
        return profiles.findById(user.getId()).orElseGet(()->UserProfile.fromUser(user));
    }
    public List<Document> search(UserProfile profile,String question,Long policyId){
        String visa=profile.getVisaType()==null?"":profile.getVisaType().trim().toUpperCase(Locale.ROOT);
        if(!visa.matches("[A-Z]{1,3}-[0-9]{1,2}[A-Z]?"))return List.of();
        String filter="visaType == '"+visa+"'"+(policyId==null?"":" && policyId == '"+policyId+"'");
        return vectors.similaritySearch(SearchRequest.builder().query(question).topK(5)
                .similarityThreshold(0.55).filterExpression(filter).build()).stream()
                .filter(d->visa.equals(d.getMetadata().get("visaType")))
                .filter(d->policyId==null || policyId.toString().equals(String.valueOf(d.getMetadata().get("policyId"))))
                .toList();
    }
}
