package io.gazetteer.osm.rocksdb;

import com.google.protobuf.InvalidProtocolBufferException;
import io.gazetteer.osm.model.Info;
import io.gazetteer.osm.model.Node;
import io.gazetteer.osm.model.User;

public class NodeType implements EntityType<Node> {

  @Override
  public byte[] serialize(Node entity) {
    return Rocksdb.Node.newBuilder()
        .setId(entity.getInfo().getId())
        .setVersion(entity.getInfo().getVersion())
        .setUid(entity.getInfo().getUid())
        .setTimestamp(entity.getInfo().getTimestamp())
        .setChangeset(entity.getInfo().getChangeset())
        .setLon(entity.getLon())
        .setLat(entity.getLat())
        .putAllTags(entity.getInfo().getTags())
        .build()
        .toByteArray();
  }

  @Override
  public Node deserialize(byte[] bytes) throws InvalidProtocolBufferException {
    Rocksdb.Node node = Rocksdb.Node.parseFrom(bytes);
    Info info =
        new Info(
            node.getId(),
            node.getVersion(),
            node.getTimestamp(),
            node.getChangeset(),
            node.getUid(),
            node.getTagsMap());
    return new Node(info, node.getLon(), node.getLat());
  }
}
