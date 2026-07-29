package dev.rstrickland.chat.net.api;

import dev.rstrickland.chat.model.ChatModels;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Messaging service (port 3003). conversationIds contain '#' (dm#a#b / grp#uuid);
 * Retrofit URL-encodes @Path values by default, so they survive as one segment
 * (matching how the web client %23-encodes them).
 */
public interface MessagingApi {

    @GET("conversations")
    Call<ChatModels.ConversationsResponse> listConversations();

    @GET("conversations/{conversationId}/messages")
    Call<ChatModels.HistoryResponse> history(@Path("conversationId") String conversationId);

    @POST("conversations/{conversationId}/messages")
    Call<ChatModels.Message> send(@Path("conversationId") String conversationId,
                                  @Body ChatModels.SendRequest body);

    /** Record a position-based receipt (kind: delivered|read). Fire-and-forget. */
    @POST("conversations/{conversationId}/receipts")
    Call<Void> sendReceipt(@Path("conversationId") String conversationId,
                           @Body ChatModels.ReceiptRequest body);
}
