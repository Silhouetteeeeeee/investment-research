package com.investmentresearch.data.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageMapper {
    @Insert(
            "INSERT INTO message (conversation_id, seq, role, payload_json, created_at)"
                    + " VALUES (#{conversationId}, #{seq}, #{role}, #{payloadJson}, #{createdAt})")
    int insertMessage(MessageEntity row);

    @Select("SELECT COALESCE(MAX(seq), 0) FROM message WHERE conversation_id = #{conversationId}")
    int maxSeq(@Param("conversationId") long conversationId);

    @Select(
            "SELECT role, payload_json AS payloadJson FROM message"
                    + " WHERE conversation_id = #{conversationId} ORDER BY seq")
    List<MessageEntity> listByConversation(@Param("conversationId") long conversationId);

    @Delete("DELETE FROM message WHERE conversation_id = #{conversationId}")
    int deleteByConversation(@Param("conversationId") long conversationId);
}
