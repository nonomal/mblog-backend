package st.coo.memo.dto.user;

import lombok.Data;

@Data
public class LoginResponse {
    private String token;
    private String username;
    private String role;
    private int userId;
    private String defaultVisibility;
    private String defaultEnableComment;
}
