package edu.campusbuddy.chat.infrastructure;

import edu.campusbuddy.shared.Sql;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MediaRepository {
  private final Sql sql;

  public MediaRepository(Sql s) {
    sql = s;
  }

  public void record(
      UUID id,
      UUID uploader,
      String originalName,
      String contentType,
      long size,
      String storagePath) {
    sql.update(
        "insert into media_file(id,uploader_id,file_name,content_type,file_size,storage_path)"
            + " values(?,?,?,?,?,?)",
        id,
        uploader,
        originalName,
        contentType,
        size,
        storagePath);
  }

  public boolean ownedBy(UUID uploader, String storagePath) {
    return sql.count(
            "select count(*) from media_file where uploader_id=? and storage_path=?",
            uploader,
            storagePath)
        > 0;
  }
}
