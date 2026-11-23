import Foundation

final class LruCache<Key: Hashable, Value> {
    private final class Node {
        let key: Key
        var value: Value
        var prev: Node?
        var next: Node?

        init(key: Key, value: Value) {
            self.key = key
            self.value = value
        }
    }

    private let capacity: Int
    private let lock = NSLock()
    private var map: [Key: Node] = [:]
    private var head: Node?
    private var tail: Node?

    init(capacity: Int) {
        self.capacity = max(1, capacity)
    }

    func get(_ key: Key) -> Value? {
        lock.lock()
        defer { lock.unlock() }
        guard let node = map[key] else { return nil }
        moveToHead(node)
        return node.value
    }

    func set(_ value: Value, forKey key: Key) {
        lock.lock()
        defer { lock.unlock() }
        if let existing = map[key] {
            existing.value = value
            moveToHead(existing)
            return
        }
        let node = Node(key: key, value: value)
        map[key] = node
        insertAtHead(node)
        if map.count > capacity, let lru = tail {
            removeNode(lru)
            map.removeValue(forKey: lru.key)
        }
    }

    func removeAll() {
        lock.lock()
        defer { lock.unlock() }
        map.removeAll()
        head = nil
        tail = nil
    }

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return map.count
    }

    private func insertAtHead(_ node: Node) {
        node.prev = nil
        node.next = head
        head?.prev = node
        head = node
        if tail == nil {
            tail = node
        }
    }

    private func removeNode(_ node: Node) {
        let prev = node.prev
        let next = node.next
        if let prev {
            prev.next = next
        } else {
            head = next
        }
        if let next {
            next.prev = prev
        } else {
            tail = prev
        }
        node.prev = nil
        node.next = nil
    }

    private func moveToHead(_ node: Node) {
        guard head !== node else { return }
        removeNode(node)
        insertAtHead(node)
    }
}
