package com.agriunified.detection;

/** 农事问答配置：智谱 GLM。key 由构建时环境变量 ZHIPU_API_KEY 注入（BuildConfig），
 *  源码不存凭据；未注入时聊天页显示配置提示，检测功能不受影响。 */
public final class ApiConfig {

    public static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    /** 构建注入：gradle assembleDebug 前 set ZHIPU_API_KEY=...（空则打包为无 key 版） */
    public static final String ZHIPU_API_KEY = BuildConfig.ZHIPU_API_KEY;
    /** 免费档；不可用时可改为 glm-4-air / glm-4.6 */
    public static final String ZHIPU_MODEL = "glm-4-flash";

    private ApiConfig() {}
}
