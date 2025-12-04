#!/usr/bin/env python3
"""
Interactive Article Database Manager

This script provides a terminal interface to analyze and manage articles in the MongoDB database.
Features:
- View articles by source, word count, and tag health
- Delete articles by word count ranges
- Delete articles from specific sources
- Preserve selected sources while cleaning others
- Remove articles based on tag coverage (no tags, single-tag, custom ranges)
- Combine source, word-count, tag-count, and date filters for bespoke cleanups
- Safe deletion with confirmation prompts
"""

from collections import defaultdict, Counter
import re
from datetime import datetime
from typing import List, Dict, Tuple, Optional, Any

from script_helper import setup_logging, connect_to_database

class ArticleManager:
    TAG_BUCKET_LABELS = {
        'no_tags': 'No tags',
        'single_tag': 'Single tag',
        'two_three_tags': '2-3 tags',
        'four_plus_tags': '4+ tags'
    }

    def __init__(self):
        self.logger = setup_logging("article_manager")
        self.client, self.db, self.collection = connect_to_database()
    
    def count_words(self, text: str) -> int:
        """Count words in text content"""
        if not text:
            return 0
        # Remove HTML tags and count words
        clean_text = re.sub(r'<[^>]+>', ' ', text)
        words = re.findall(r'\b\w+\b', clean_text.lower())
        return len(words)

    def _normalize_tags(self, raw_tags: Any) -> List[str]:
        """Return a clean list of tag strings for an article"""
        if not raw_tags:
            return []

        if isinstance(raw_tags, list):
            normalized = []
            for tag in raw_tags:
                if tag is None:
                    continue
                tag_str = str(tag).strip()
                if tag_str:
                    normalized.append(tag_str)
            return normalized

        # Some pipelines may store tags as comma separated strings
        if isinstance(raw_tags, str):
            parts = [part.strip() for part in raw_tags.split(',')]
            return [part for part in parts if part]

        return []

    def _tag_bucket_from_count(self, tag_count: int) -> str:
        """Map a tag count to a health bucket label key"""
        if tag_count <= 0:
            return 'no_tags'
        if tag_count == 1:
            return 'single_tag'
        if tag_count <= 3:
            return 'two_three_tags'
        return 'four_plus_tags'
    
    def get_article_stats(self) -> Dict:
        """Get comprehensive statistics about articles in the database"""
        print("📊 Analyzing articles in database...")
        
        articles = list(self.collection.find({}, {
            'title': 1,
            'content': 1,
            'summary': 1,
            'source': 1,
            'publishDate': 1,
            'contentLength': 1,
            'tags': 1
        }))
        
        if not articles:
            print("❌ No articles found in database!")
            return {}
        
        stats = {
            'total_articles': len(articles),
            'by_source': defaultdict(int),
            'by_word_count': defaultdict(int),
            'source_word_stats': defaultdict(lambda: defaultdict(int)),
            'word_count_ranges': {
                'very_short': 0,    # < 20 words
                'short': 0,         # 20-50 words
                'medium': 0,        # 51-200 words
                'long': 0,          # 201-500 words
                'very_long': 0      # > 500 words
            },
            'tag_count_distribution': defaultdict(int),
            'tag_presence': {
                'no_tags': 0,
                'single_tag': 0,
                'two_three_tags': 0,
                'four_plus_tags': 0
            },
            'source_tag_health': defaultdict(lambda: defaultdict(int))
        }
        
        for article in articles:
            source = article.get('source', 'Unknown')
            
            # Use contentLength if available, otherwise calculate from content/summary
            if 'contentLength' in article and article['contentLength'] is not None:
                word_count = article['contentLength']
            else:
                # Fallback: Calculate word count from content or summary
                content = article.get('content', '') or article.get('summary', '') or article.get('title', '')
                word_count = self.count_words(content)
            
            # Update statistics
            stats['by_source'][source] += 1
            stats['by_word_count'][word_count] += 1
            stats['source_word_stats'][source][word_count] += 1
            
            # Categorize by word count ranges (updated for conservative tagging)
            if word_count < 20:
                stats['word_count_ranges']['very_short'] += 1
            elif word_count <= 50:
                stats['word_count_ranges']['short'] += 1
            elif word_count <= 200:
                stats['word_count_ranges']['medium'] += 1
            elif word_count <= 500:
                stats['word_count_ranges']['long'] += 1
            else:
                stats['word_count_ranges']['very_long'] += 1

            # Tag statistics
            tags = self._normalize_tags(article.get('tags'))
            tag_count = len(tags)
            stats['tag_count_distribution'][tag_count] += 1

            tag_bucket = self._tag_bucket_from_count(tag_count)
            stats['tag_presence'][tag_bucket] += 1
            stats['source_tag_health'][source][tag_bucket] += 1
        
        return stats
    
    def display_stats(self, stats: Dict):
        """Display article statistics in a formatted way"""
        print("\n" + "="*60)
        print("📈 ARTICLE DATABASE STATISTICS")
        print("="*60)
        
        print(f"\n📚 Total Articles: {stats['total_articles']:,}")
        
        # Word count distribution
        print(f"\n📏 Articles by Word Count:")
        ranges = stats['word_count_ranges']
        print(f"   Very Short (< 20 words):   {ranges['very_short']:,}")
        print(f"   Short (20-50 words):       {ranges['short']:,}")
        print(f"   Medium (51-200 words):     {ranges['medium']:,}")
        print(f"   Long (201-500 words):      {ranges['long']:,}")
        print(f"   Very Long (> 500 words):   {ranges['very_long']:,}")

        tag_presence = stats.get('tag_presence')
        if tag_presence:
            total_tag_samples = sum(tag_presence.values()) or 1
            print(f"\n🏷️  Articles by Tag Coverage:")
            for bucket_key in ['no_tags', 'single_tag', 'two_three_tags', 'four_plus_tags']:
                bucket_label = self.TAG_BUCKET_LABELS[bucket_key]
                bucket_value = tag_presence.get(bucket_key, 0)
                percentage = (bucket_value / total_tag_samples) * 100
                print(f"   {bucket_label:<18}: {bucket_value:,} ({percentage:.1f}%)")

            zero_tag_sources = []
            single_tag_sources = []
            tag_health = stats.get('source_tag_health', {})
            for source, buckets in tag_health.items():
                no_tag_count = buckets.get('no_tags', 0)
                single_tag_count = buckets.get('single_tag', 0)
                if no_tag_count:
                    zero_tag_sources.append((source, no_tag_count))
                if single_tag_count:
                    single_tag_sources.append((source, single_tag_count))

            if zero_tag_sources:
                print(f"\n🚨 Sources with untagged articles (top 10):")
                for source, count in sorted(zero_tag_sources, key=lambda x: x[1], reverse=True)[:10]:
                    percentage = (count / stats['total_articles']) * 100
                    print(f"   {source:<25} {count:,} missing tags ({percentage:.1f}%)")

            if single_tag_sources:
                print(f"\n⚠️ Sources with single-tag articles (top 10):")
                for source, count in sorted(single_tag_sources, key=lambda x: x[1], reverse=True)[:10]:
                    percentage = (count / stats['total_articles']) * 100
                    print(f"   {source:<25} {count:,} single-tag ({percentage:.1f}%)")
        
        # Top sources
        print(f"\n📰 Articles by Source:")
        sorted_sources = sorted(stats['by_source'].items(), key=lambda x: x[1], reverse=True)
        for i, (source, count) in enumerate(sorted_sources[:15], 1):
            percentage = (count / stats['total_articles']) * 100
            print(f"   {i:2d}. {source:<25} {count:,} ({percentage:.1f}%)")
        
        if len(sorted_sources) > 15:
            remaining = len(sorted_sources) - 15
            print(f"   ... and {remaining} more sources")
    
    def display_word_count_analysis(self, stats: Dict):
        """Display detailed word count analysis by source"""
        print("\n" + "="*80)
        print("📊 DETAILED WORD COUNT ANALYSIS BY SOURCE")
        print("="*80)
        
        source_stats = stats['source_word_stats']
        
        for source in sorted(source_stats.keys()):
            word_counts = source_stats[source]
            total_articles = sum(word_counts.values())
            
            # Calculate distribution
            very_short = sum(count for wc, count in word_counts.items() if wc < 20)
            short = sum(count for wc, count in word_counts.items() if 20 <= wc <= 50)
            medium = sum(count for wc, count in word_counts.items() if 51 <= wc <= 200)
            long_articles = sum(count for wc, count in word_counts.items() if 201 <= wc <= 500)
            very_long = sum(count for wc, count in word_counts.items() if wc > 500)
            
            print(f"\n📰 {source} ({total_articles:,} articles):")
            print(f"   < 20 words:     {very_short:,} ({very_short/total_articles*100:.1f}%)")
            print(f"   20-50 words:    {short:,} ({short/total_articles*100:.1f}%)")
            print(f"   51-200 words:   {medium:,} ({medium/total_articles*100:.1f}%)")
            print(f"   201-500 words:  {long_articles:,} ({long_articles/total_articles*100:.1f}%)")
            print(f"   > 500 words:    {very_long:,} ({very_long/total_articles*100:.1f}%)")

            tag_buckets = stats.get('source_tag_health', {}).get(source)
            if tag_buckets:
                total_by_source = sum(tag_buckets.values()) or 1
                print(f"   Tag coverage:   ", end="")
                bucket_summaries = []
                for bucket_key in ['no_tags', 'single_tag', 'two_three_tags', 'four_plus_tags']:
                    bucket_value = tag_buckets.get(bucket_key, 0)
                    if bucket_value:
                        label = self.TAG_BUCKET_LABELS[bucket_key]
                        percentage = (bucket_value / total_by_source) * 100
                        bucket_summaries.append(f"{label} {bucket_value:,} ({percentage:.1f}%)")
                if bucket_summaries:
                    print("; ".join(bucket_summaries))
                else:
                    print("No tag data")
    
    def get_articles_by_criteria(
        self,
        min_words: Optional[int] = None,
        max_words: Optional[int] = None,
        sources: Optional[List[str]] = None,
        exclude_sources: Optional[List[str]] = None,
        min_tags: Optional[int] = None,
        max_tags: Optional[int] = None,
        missing_tags_only: bool = False,
        has_tags: Optional[bool] = None,
        required_tags: Optional[List[str]] = None,
        excluded_tags: Optional[List[str]] = None,
        before_date: Optional[datetime] = None,
        after_date: Optional[datetime] = None
    ) -> List[Dict]:
        """Return articles that satisfy the provided filters"""
        print(f"🔍 Finding articles matching criteria...")

        query: Dict[str, Any] = {}

        if sources:
            query['source'] = {'$in': sources}
        elif exclude_sources:
            query['source'] = {'$nin': exclude_sources}

        if before_date or after_date:
            date_query: Dict[str, Any] = {}
            if after_date:
                date_query['$gte'] = after_date
            if before_date:
                date_query['$lte'] = before_date
            query['publishDate'] = date_query

        if min_words is not None or max_words is not None:
            content_length_query: Dict[str, Any] = {}
            if min_words is not None:
                content_length_query['$gte'] = min_words
            if max_words is not None:
                content_length_query['$lte'] = max_words
            query['contentLength'] = content_length_query

        projection = {
            'title': 1,
            'content': 1,
            'summary': 1,
            'source': 1,
            'publishDate': 1,
            'contentLength': 1,
            'tags': 1,
            'url': 1
        }

        articles = list(self.collection.find(query, projection))

        required_tag_set = {tag.strip().lower() for tag in required_tags} if required_tags else set()
        excluded_tag_set = {tag.strip().lower() for tag in excluded_tags} if excluded_tags else set()

        matching_articles = []
        for article in articles:
            if 'contentLength' in article and article['contentLength'] is not None:
                word_count = article['contentLength']
            else:
                content = article.get('content', '') or article.get('summary', '') or article.get('title', '')
                word_count = self.count_words(content)

            if min_words is not None and word_count < min_words:
                continue
            if max_words is not None and word_count > max_words:
                continue

            tags = self._normalize_tags(article.get('tags'))
            tag_count = len(tags)

            if missing_tags_only and tag_count > 0:
                continue
            if has_tags is True and tag_count == 0:
                continue
            if has_tags is False and tag_count > 0:
                continue
            if min_tags is not None and tag_count < min_tags:
                continue
            if max_tags is not None and tag_count > max_tags:
                continue

            if required_tag_set or excluded_tag_set:
                tag_lookup = {tag.lower() for tag in tags}
                if required_tag_set and not required_tag_set.issubset(tag_lookup):
                    continue
                if excluded_tag_set and excluded_tag_set.intersection(tag_lookup):
                    continue

            article['word_count'] = word_count
            article['tags'] = tags
            article['tag_count'] = tag_count
            matching_articles.append(article)

        return matching_articles

    def summarize_articles(self, articles: List[Dict], show_tag_buckets: bool = False):
        """Print a concise summary of selected articles"""
        if not articles:
            print("   (no articles)")
            return

        by_source = Counter()
        tag_buckets = Counter()

        for article in articles:
            by_source[article.get('source', 'Unknown')] += 1
            if show_tag_buckets:
                bucket = self._tag_bucket_from_count(article.get('tag_count', 0))
                tag_buckets[bucket] += 1

        print("   By source:")
        for source, count in sorted(by_source.items(), key=lambda x: x[1], reverse=True):
            print(f"     • {source}: {count:,}")

        if show_tag_buckets and tag_buckets:
            print("   Tag coverage:")
            for bucket_key, count in sorted(tag_buckets.items(), key=lambda x: x[1], reverse=True):
                label = self.TAG_BUCKET_LABELS.get(bucket_key, bucket_key)
                print(f"     • {label}: {count:,}")

    def preview_articles(self, articles: List[Dict], limit: int = 10):
        """Show a quick preview of the first few articles"""
        if not articles:
            return

        preview_count = min(limit, len(articles))
        print(f"\n🧐 Previewing first {preview_count} articles:")
        for article in articles[:preview_count]:
            title = article.get('title') or 'Untitled'
            source = article.get('source', 'Unknown')
            word_count = article.get('word_count', '?')
            tag_count = article.get('tag_count', 0)
            publish_date = article.get('publishDate')
            publish_label = publish_date.strftime('%Y-%m-%d') if isinstance(publish_date, datetime) else publish_date
            url = article.get('url')

            print(f"   • {title}")
            print(f"     Source: {source} | Words: {word_count} | Tags: {tag_count}")
            if publish_label:
                print(f"     Published: {publish_label}")
            if url:
                print(f"     URL: {url}")

    def _print_source_catalog(self, sources: List[Tuple[str, int]]):
        """Pretty-print available sources with counts"""
        for index, (source, count) in enumerate(sources, 1):
            print(f"   {index:2d}. {source} ({count:,} articles)")

    def _sorted_sources(self, stats: Dict) -> List[Tuple[str, int]]:
        """Return sources sorted by article count descending"""
        return sorted(stats['by_source'].items(), key=lambda x: x[1], reverse=True)

    def _select_sources(self, sources: List[Tuple[str, int]], prompt: str) -> List[str]:
        """Prompt user to select sources by index and return their names"""
        if not sources:
            print("❌ No sources available to select.")
            return []

        self._print_source_catalog(sources)
        selection = input(prompt).strip()
        if not selection:
            return []

        try:
            indices = [int(token.strip()) - 1 for token in selection.split(',') if token.strip()]
        except ValueError:
            print("❌ Invalid selection. Please use comma-separated numbers like 1,3,5.")
            return []

        chosen = [sources[i][0] for i in indices if 0 <= i < len(sources)]
        if not chosen:
            print("❌ No valid sources selected.")
        return chosen
    
    def delete_articles(self, article_ids: List[str]) -> int:
        """Delete articles by their IDs"""
        if not article_ids:
            return 0
        
        try:
            result = self.collection.delete_many({'_id': {'$in': article_ids}})
            return result.deleted_count
        except Exception as e:
            print(f"❌ Error deleting articles: {e}")
            return 0
    
    def interactive_menu(self):
        """Main interactive menu"""
        while True:
            print("\n" + "="*60)
            print("🗞️  ARTICLE DATABASE MANAGER")
            print("="*60)
            print("1. 📊 View database statistics")
            print("2. 🔍 Analyze articles by word count and source")
            print("3. 🗑️  Delete articles by word count range")
            print("4. 🎯 Delete articles from specific sources")
            print("5. 🛡️  Delete articles from all sources EXCEPT selected ones")
            print("6. 🧹 Clean up very short articles (< 20 words)")
            print("7. 📈 Export statistics to file")
            print("8. 🏷️  Delete articles by tag coverage")
            print("9. 🧮 Custom cleanup (combine filters)")
            print("0. 🚪 Exit")
            print("-" * 60)
            
            choice = input("Enter your choice (0-9): ").strip()
            
            if choice == '0':
                print("👋 Goodbye!")
                break
            elif choice == '1':
                self.show_statistics()
            elif choice == '2':
                self.analyze_word_counts()
            elif choice == '3':
                self.delete_by_word_count()
            elif choice == '4':
                self.delete_by_sources()
            elif choice == '5':
                self.delete_except_sources()
            elif choice == '6':
                self.cleanup_short_articles()
            elif choice == '7':
                self.export_statistics()
            elif choice == '8':
                self.delete_by_tag_coverage()
            elif choice == '9':
                self.custom_cleanup()
            else:
                print("❌ Invalid choice. Please try again.")
    
    def show_statistics(self):
        """Show database statistics"""
        stats = self.get_article_stats()
        if stats:
            self.display_stats(stats)
    
    def analyze_word_counts(self):
        """Analyze articles by word count and source"""
        stats = self.get_article_stats()
        if stats:
            self.display_word_count_analysis(stats)
    
    def delete_by_word_count(self):
        """Delete articles by word count range"""
        print("\n🗑️  DELETE ARTICLES BY WORD COUNT")
        print("-" * 40)
        
        try:
            min_words = input("Enter minimum word count (or press Enter for no minimum): ").strip()
            min_words = int(min_words) if min_words else None
            
            max_words = input("Enter maximum word count (or press Enter for no maximum): ").strip()
            max_words = int(max_words) if max_words else None
            
            if min_words is None and max_words is None:
                print("❌ You must specify at least one limit.")
                return
            
            # Find matching articles
            articles = self.get_articles_by_criteria(min_words=min_words, max_words=max_words)
            
            if not articles:
                print("✅ No articles found matching the criteria.")
                return
            
            # Show preview
            print(f"\n📋 Found {len(articles):,} articles to delete:")
            self.summarize_articles(articles)
            self.preview_articles(articles, limit=5)
            
            # Confirm deletion
            range_desc = f"{min_words or 0}-{max_words or '∞'} words"
            confirm = input(f"\n⚠️  Delete {len(articles):,} articles with {range_desc}? (yes/no): ").strip().lower()
            
            if confirm == 'yes':
                article_ids = [article['_id'] for article in articles]
                deleted_count = self.delete_articles(article_ids)
                print(f"✅ Successfully deleted {deleted_count:,} articles!")
            else:
                print("❌ Deletion cancelled.")
                
        except ValueError:
            print("❌ Invalid number format. Please enter valid integers.")
        except Exception as e:
            print(f"❌ Error: {e}")
    
    def delete_by_sources(self):
        """Delete articles from specific sources"""
        print("\n🎯 DELETE ARTICLES FROM SPECIFIC SOURCES")
        print("-" * 45)
        
        stats = self.get_article_stats()
        if not stats:
            return
        
        # Show available sources
        print("\n📰 Available sources:")
        sources = self._sorted_sources(stats)

        selected_sources = self._select_sources(
            sources,
            "\nEnter source numbers to DELETE (comma-separated, e.g., 1,3,5): "
        )

        if not selected_sources:
            print("❌ No sources selected.")
            return

        try:
            # Find articles to delete
            articles = self.get_articles_by_criteria(sources=selected_sources)
            
            if not articles:
                print("✅ No articles found from selected sources.")
                return
            
            # Show preview
            print(f"\n📋 Articles to delete:")
            self.summarize_articles(articles)
            self.preview_articles(articles, limit=5)
            
            # Confirm deletion
            confirm = input(f"\n⚠️  Delete {len(articles):,} articles from {len(selected_sources)} sources? (yes/no): ").strip().lower()
            
            if confirm == 'yes':
                article_ids = [article['_id'] for article in articles]
                deleted_count = self.delete_articles(article_ids)
                print(f"✅ Successfully deleted {deleted_count:,} articles!")
            else:
                print("❌ Deletion cancelled.")
                
        except Exception as e:
            print(f"❌ Error: {e}")
    
    def delete_except_sources(self):
        """Delete articles from all sources except selected ones"""
        print("\n🛡️  DELETE FROM ALL SOURCES EXCEPT SELECTED")
        print("-" * 45)
        
        stats = self.get_article_stats()
        if not stats:
            return
        
        # Show available sources
        print("\n📰 Available sources:")
        sources = self._sorted_sources(stats)

        keep_sources = self._select_sources(
            sources,
            "\nEnter source numbers to KEEP (comma-separated, e.g., 1,3,5): "
        )

        if not keep_sources:
            print("❌ No sources selected to preserve.")
            return

        try:
            # Find articles to delete (all except selected)
            articles = self.get_articles_by_criteria(exclude_sources=keep_sources)
            
            if not articles:
                print("✅ No articles found to delete.")
                return
            
            # Show preview
            print(f"\n📋 Articles to DELETE (from sources NOT in preserve list):")
            self.summarize_articles(articles)
            self.preview_articles(articles, limit=5)

            print(f"\n🛡️  Sources to PRESERVE:")
            for source in keep_sources:
                count = stats['by_source'].get(source, 0)
                print(f"   {source}: {count:,} articles")
            
            # Confirm deletion
            distinct_sources = {article.get('source', 'Unknown') for article in articles}
            confirm = input(f"\n⚠️  Delete {len(articles):,} articles from {len(distinct_sources)} sources? (yes/no): ").strip().lower()
            
            if confirm == 'yes':
                article_ids = [article['_id'] for article in articles]
                deleted_count = self.delete_articles(article_ids)
                print(f"✅ Successfully deleted {deleted_count:,} articles!")
                print(f"🛡️  Preserved articles from {len(keep_sources)} selected sources.")
            else:
                print("❌ Deletion cancelled.")
                
        except Exception as e:
            print(f"❌ Error: {e}")
    
    def delete_by_tag_coverage(self):
        """Delete articles based on tag presence or tag-count bands"""
        print("\n🏷️  DELETE ARTICLES BY TAG COVERAGE")
        print("-" * 45)

        stats = self.get_article_stats()
        if not stats:
            return

        print("Select a tag coverage rule:")
        print("  1. Delete articles with no tags")
        print("  2. Delete articles with one tag or fewer")
        print("  3. Delete articles within a custom tag range")

        choice = input("Choose option (1-3): ").strip()
        filters: Dict[str, Any] = {}
        description = ""

        if choice == '1':
            filters['missing_tags_only'] = True
            description = "articles with no tags"
        elif choice == '2':
            filters['max_tags'] = 1
            description = "articles with one tag or fewer"
        elif choice == '3':
            min_tags_input = input("Minimum tags (press Enter to skip): ").strip()
            max_tags_input = input("Maximum tags (press Enter to skip): ").strip()

            try:
                min_tags = int(min_tags_input) if min_tags_input else None
                max_tags = int(max_tags_input) if max_tags_input else None
            except ValueError:
                print("❌ Tag counts must be integers.")
                return

            if min_tags is None and max_tags is None:
                print("❌ You must provide at least one tag boundary.")
                return

            if min_tags is not None and min_tags < 0:
                print("❌ Minimum tags cannot be negative.")
                return
            if max_tags is not None and max_tags < 0:
                print("❌ Maximum tags cannot be negative.")
                return
            if min_tags is not None and max_tags is not None and min_tags > max_tags:
                print("❌ Minimum tags cannot exceed maximum tags.")
                return

            if min_tags is not None:
                filters['min_tags'] = min_tags
            if max_tags is not None:
                filters['max_tags'] = max_tags

            if min_tags is not None and max_tags is not None:
                description = f"articles with {min_tags}-{max_tags} tags"
            elif min_tags is not None:
                description = f"articles with ≥ {min_tags} tags"
            else:
                description = f"articles with ≤ {max_tags} tags"
        else:
            print("❌ Invalid selection.")
            return

        sources = self._sorted_sources(stats)
        print("\nSource scope:")
        print("  A. Apply to ALL sources")
        print("  B. Apply ONLY to selected sources")
        print("  C. Apply to all EXCEPT selected sources")

        scope = input("Choose scope (A/B/C): ").strip().lower()
        if scope == 'b':
            selected = self._select_sources(sources, "   Sources to target (comma-separated): ")
            if not selected:
                print("❌ No sources selected. Aborting.")
                return
            filters['sources'] = selected
            description += f" in {len(selected)} source(s)"
        elif scope == 'c':
            preserved = self._select_sources(sources, "   Sources to keep untouched (comma-separated): ")
            if not preserved:
                print("❌ No sources selected to preserve. Aborting.")
                return
            filters['exclude_sources'] = preserved
            description += f", excluding {len(preserved)} source(s)"
        else:
            description += " across all sources"

        try:
            articles = self.get_articles_by_criteria(**filters)
        except Exception as exc:
            print(f"❌ Error while fetching articles: {exc}")
            return

        if not articles:
            print("✅ No articles matched the selected tag filters.")
            return

        print(f"\n📋 Found {len(articles):,} {description}:")
        self.summarize_articles(articles, show_tag_buckets=True)
        self.preview_articles(articles, limit=5)

        confirm = input(f"\n⚠️  Delete {len(articles):,} {description}? (yes/no): ").strip().lower()
        if confirm != 'yes':
            print("❌ Deletion cancelled.")
            return

        article_ids = [article['_id'] for article in articles]
        deleted = self.delete_articles(article_ids)
        print(f"✅ Deleted {deleted:,} articles based on tag coverage filter.")

    def custom_cleanup(self):
        """Run an interactive wizard to combine source, tag, and word filters"""
        print("\n🧮 CUSTOM CLEANUP WIZARD")
        print("-" * 45)

        stats = self.get_article_stats()
        if not stats:
            return

        filters: Dict[str, Any] = {}
        summary_lines: List[str] = []

        sources = self._sorted_sources(stats)
        print("\nSource filtering:")
        print("  A. No source filter")
        print("  B. Target specific sources")
        print("  C. Preserve specific sources (delete the rest)")
        source_choice = input("Choose source mode (A/B/C): ").strip().lower()

        if source_choice == 'b':
            target_sources = self._select_sources(sources, "   Sources to target (comma-separated): ")
            if not target_sources:
                print("❌ No sources selected. Aborting.")
                return
            filters['sources'] = target_sources
            summary_lines.append(f"Target sources: {', '.join(target_sources)}")
        elif source_choice == 'c':
            protected_sources = self._select_sources(sources, "   Sources to protect (comma-separated): ")
            if not protected_sources:
                print("❌ No sources selected to protect. Aborting.")
                return
            filters['exclude_sources'] = protected_sources
            summary_lines.append(f"Preserve sources: {', '.join(protected_sources)}")

        print("\nWord count filters (press Enter to skip):")
        min_words_input = input("   Minimum words: ").strip()
        max_words_input = input("   Maximum words: ").strip()

        try:
            if min_words_input:
                filters['min_words'] = int(min_words_input)
                if filters['min_words'] < 0:
                    print("❌ Minimum words cannot be negative.")
                    return
                summary_lines.append(f"Min words: {filters['min_words']}")
            if max_words_input:
                filters['max_words'] = int(max_words_input)
                if filters['max_words'] < 0:
                    print("❌ Maximum words cannot be negative.")
                    return
                summary_lines.append(f"Max words: {filters['max_words']}")
            if 'min_words' in filters and 'max_words' in filters and filters['min_words'] > filters['max_words']:
                print("❌ Minimum words cannot exceed maximum words.")
                return
        except ValueError:
            print("❌ Word counts must be integers.")
            return

        print("\nTag coverage filters:")
        if input("   Only keep articles with NO tags? (y/N): ").strip().lower() == 'y':
            filters['missing_tags_only'] = True
            summary_lines.append("Tag filter: no tags")
        else:
            require_tags = input("   Require at least one tag? (y/N): ").strip().lower()
            if require_tags == 'y':
                filters['has_tags'] = True
                summary_lines.append("Tag filter: require tags")

            min_tags_input = input("   Minimum tag count (Enter to skip): ").strip()
            max_tags_input = input("   Maximum tag count (Enter to skip): ").strip()

            try:
                if min_tags_input:
                    filters['min_tags'] = int(min_tags_input)
                    if filters['min_tags'] < 0:
                        print("❌ Minimum tags cannot be negative.")
                        return
                    summary_lines.append(f"Min tags: {filters['min_tags']}")
                if max_tags_input:
                    filters['max_tags'] = int(max_tags_input)
                    if filters['max_tags'] < 0:
                        print("❌ Maximum tags cannot be negative.")
                        return
                    summary_lines.append(f"Max tags: {filters['max_tags']}")
                if 'min_tags' in filters and 'max_tags' in filters and filters['min_tags'] > filters['max_tags']:
                    print("❌ Minimum tags cannot exceed maximum tags.")
                    return
            except ValueError:
                print("❌ Tag counts must be integers.")
                return

            required_tags_input = input("   Required tags (comma-separated, Enter to skip): ").strip()
            if required_tags_input:
                required_tags = [tag.strip() for tag in required_tags_input.split(',') if tag.strip()]
                if required_tags:
                    filters['required_tags'] = required_tags
                    summary_lines.append(f"Must include tags: {', '.join(required_tags)}")

            excluded_tags_input = input("   Tags to avoid (comma-separated, Enter to skip): ").strip()
            if excluded_tags_input:
                excluded_tags = [tag.strip() for tag in excluded_tags_input.split(',') if tag.strip()]
                if excluded_tags:
                    filters['excluded_tags'] = excluded_tags
                    summary_lines.append(f"Must NOT include tags: {', '.join(excluded_tags)}")

        print("\nPublish date filters (YYYY-MM-DD, press Enter to skip):")
        after_input = input("   Published on/after: ").strip()
        before_input = input("   Published on/before: ").strip()

        date_format = "%Y-%m-%d"
        try:
            if after_input:
                filters['after_date'] = datetime.strptime(after_input, date_format)
                summary_lines.append(f"Published ≥ {after_input}")
            if before_input:
                filters['before_date'] = datetime.strptime(before_input, date_format)
                summary_lines.append(f"Published ≤ {before_input}")
        except ValueError:
            print("❌ Dates must follow YYYY-MM-DD format.")
            return

        if not filters:
            print("❌ No filters were provided. Nothing to do.")
            return

        print("\n🔎 Applied filters:")
        for line in summary_lines:
            print(f"   • {line}")

        try:
            articles = self.get_articles_by_criteria(**filters)
        except Exception as exc:
            print(f"❌ Error while fetching articles: {exc}")
            return

        if not articles:
            print("✅ No articles matched the custom filters.")
            return

        print(f"\n📋 Found {len(articles):,} articles matching custom filters:")
        self.summarize_articles(articles, show_tag_buckets=True)
        self.preview_articles(articles, limit=7)

        confirm = input(f"\n⚠️  Delete {len(articles):,} articles matching these filters? (yes/no): ").strip().lower()
        if confirm != 'yes':
            print("❌ Deletion cancelled.")
            return

        article_ids = [article['_id'] for article in articles]
        deleted = self.delete_articles(article_ids)
        print(f"✅ Deleted {deleted:,} articles using custom cleanup filters.")

    def cleanup_short_articles(self):
        """Clean up very short articles (< 20 words) - these won't get tags anyway"""
        print("\n🧹 CLEANUP VERY SHORT ARTICLES")
        print("-" * 35)
        print("ℹ️  Articles with < 20 words won't get tags in the new conservative system.")
        
        articles = self.get_articles_by_criteria(max_words=19)
        
        if not articles:
            print("✅ No very short articles found.")
            return
        
        print(f"\n📋 Found {len(articles):,} very short articles (< 20 words):")
        self.summarize_articles(articles)
        self.preview_articles(articles, limit=5)
        
        print(f"\n💡 These articles are too short for meaningful tagging and may be:")
        print(f"   • Headlines without content")
        print(f"   • Incomplete article imports")
        print(f"   • Low-quality content")
        
        # Confirm deletion
        confirm = input(f"\n⚠️  Delete all {len(articles):,} very short articles? (yes/no): ").strip().lower()
        
        if confirm == 'yes':
            article_ids = [article['_id'] for article in articles]
            deleted_count = self.delete_articles(article_ids)
            print(f"✅ Successfully deleted {deleted_count:,} very short articles!")
            print(f"🎯 This will improve tagging efficiency and database quality.")
        else:
            print("❌ Cleanup cancelled.")
    
    def export_statistics(self):
        """Export statistics to a file"""
        print("\n📈 EXPORT STATISTICS")
        print("-" * 20)
        
        stats = self.get_article_stats()
        if not stats:
            return
        
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"article_stats_{timestamp}.txt"
        
        try:
            with open(filename, 'w', encoding='utf-8') as f:
                f.write("ARTICLE DATABASE STATISTICS\n")
                f.write("=" * 60 + "\n")
                f.write(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n")
                
                f.write(f"Total Articles: {stats['total_articles']:,}\n\n")
                
                # Word count distribution
                f.write("Articles by Word Count:\n")
                ranges = stats['word_count_ranges']
                f.write(f"  Very Short (< 20 words):   {ranges['very_short']:,}\n")
                f.write(f"  Short (20-50 words):       {ranges['short']:,}\n")
                f.write(f"  Medium (51-200 words):     {ranges['medium']:,}\n")
                f.write(f"  Long (201-500 words):      {ranges['long']:,}\n")
                f.write(f"  Very Long (> 500 words):   {ranges['very_long']:,}\n\n")

                tag_presence = stats.get('tag_presence')
                if tag_presence:
                    f.write("Articles by Tag Coverage:\n")
                    total_tag_samples = sum(tag_presence.values()) or 1
                    for bucket_key in ['no_tags', 'single_tag', 'two_three_tags', 'four_plus_tags']:
                        bucket_label = self.TAG_BUCKET_LABELS[bucket_key]
                        bucket_value = tag_presence.get(bucket_key, 0)
                        percentage = (bucket_value / total_tag_samples) * 100
                        f.write(f"  {bucket_label:<20} {bucket_value:,} ({percentage:.1f}%)\n")

                    tag_health = stats.get('source_tag_health', {})
                    if tag_health:
                        zero_tag_sources = sorted(
                            [(source, buckets.get('no_tags', 0)) for source, buckets in tag_health.items() if buckets.get('no_tags', 0)],
                            key=lambda item: item[1],
                            reverse=True
                        )
                        if zero_tag_sources:
                            f.write("\n  Sources with missing tags (top 10):\n")
                            for source, count in zero_tag_sources[:10]:
                                percentage = (count / stats['total_articles']) * 100
                                f.write(f"    {source:<30} {count:,} ({percentage:.1f}%)\n")

                        single_tag_sources = sorted(
                            [(source, buckets.get('single_tag', 0)) for source, buckets in tag_health.items() if buckets.get('single_tag', 0)],
                            key=lambda item: item[1],
                            reverse=True
                        )
                        if single_tag_sources:
                            f.write("\n  Sources with single-tag articles (top 10):\n")
                            for source, count in single_tag_sources[:10]:
                                percentage = (count / stats['total_articles']) * 100
                                f.write(f"    {source:<30} {count:,} ({percentage:.1f}%)\n")

                    f.write("\n")
                
                # Sources
                f.write("Articles by Source:\n")
                sorted_sources = sorted(stats['by_source'].items(), key=lambda x: x[1], reverse=True)
                for i, (source, count) in enumerate(sorted_sources, 1):
                    percentage = (count / stats['total_articles']) * 100
                    f.write(f"  {i:2d}. {source:<30} {count:,} ({percentage:.1f}%)\n")
                
                # Detailed analysis
                f.write("\n" + "="*80 + "\n")
                f.write("DETAILED WORD COUNT ANALYSIS BY SOURCE\n")
                f.write("="*80 + "\n")
                
                source_stats = stats['source_word_stats']
                for source in sorted(source_stats.keys()):
                    word_counts = source_stats[source]
                    total_articles = sum(word_counts.values())
                    
                    very_short = sum(count for wc, count in word_counts.items() if wc < 20)
                    short = sum(count for wc, count in word_counts.items() if 20 <= wc <= 50)
                    medium = sum(count for wc, count in word_counts.items() if 51 <= wc <= 200)
                    long_articles = sum(count for wc, count in word_counts.items() if 201 <= wc <= 500)
                    very_long = sum(count for wc, count in word_counts.items() if wc > 500)
                    
                    f.write(f"\n{source} ({total_articles:,} articles):\n")
                    f.write(f"  < 20 words:     {very_short:,} ({very_short/total_articles*100:.1f}%)\n")
                    f.write(f"  20-50 words:    {short:,} ({short/total_articles*100:.1f}%)\n")
                    f.write(f"  51-200 words:   {medium:,} ({medium/total_articles*100:.1f}%)\n")
                    f.write(f"  201-500 words:  {long_articles:,} ({long_articles/total_articles*100:.1f}%)\n")
                    f.write(f"  > 500 words:    {very_long:,} ({very_long/total_articles*100:.1f}%)\n")
            
            print(f"✅ Statistics exported to: {filename}")
            
        except Exception as e:
            print(f"❌ Error exporting statistics: {e}")
    
    def close(self):
        """Close database connection"""
        if self.client:
            self.client.close()

def main():
    """Main function"""
    print("🗞️  Article Database Manager")
    print("=" * 40)
    
    try:
        manager = ArticleManager()
        manager.interactive_menu()
    except KeyboardInterrupt:
        print("\n\n👋 Interrupted by user. Goodbye!")
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")
    finally:
        try:
            manager.close()
        except:
            pass

if __name__ == "__main__":
    main()