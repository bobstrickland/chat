package dev.rstrickland.chat.net.api;

import dev.rstrickland.chat.model.ContactModels;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Contacts endpoints, served by the Profile service (port 3002). Self-only —
 * every call is keyed by the caller's token (added by the interceptor).
 */
public interface ContactsApi {

    @GET("contacts")
    Call<ContactModels.ContactsResponse> list();

    @POST("contacts")
    Call<ContactModels.Contact> add(@Body ContactModels.AddRequest body);

    @DELETE("contacts/{contactId}")
    Call<Void> remove(@Path("contactId") String contactId);
}
