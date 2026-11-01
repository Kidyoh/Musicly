/// A podcast or music RSS feed the user added.
class RssFeed {
  const RssFeed({required this.url, required this.title, this.imageUrl});

  final String url;
  final String title;
  final String? imageUrl;

  Map<String, dynamic> toJson() => {
    'url': url,
    'title': title,
    'imageUrl': imageUrl,
  };

  factory RssFeed.fromJson(Map<String, dynamic> j) => RssFeed(
    url: j['url'] as String,
    title: j['title'] as String? ?? 'Feed',
    imageUrl: j['imageUrl'] as String?,
  );
}
