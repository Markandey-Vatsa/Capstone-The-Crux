#!/usr/bin/env python3
"""
Domain Reassignment Script

This script identifies articles with zero domains assigned and attempts to assign domains
based on their keyword tags using the current taxonomy mappings. It processes articles
in batches and provides comprehensive logging and error handling.

Features:
- Identifies articles without domains (empty or null tags array)
- Loads current taxonomy from base and custom taxonomy files
- Maps keyword tags to domains using taxonomy lookup
- Updates articles in MongoDB with new domain assignments
- Comprehensive logging and error handling
- Batch processing for memory efficiency
"""

import sys
import json
from datetime import datetime
from typing import List, Dict, Optional, Set
from pathlib import Path
import time

from script_helper import setup_logging, connect_to_database

BACKEND_ROOT = Path(__file__).resolve().parents[1]

class DomainReassignmentScript:
    def __init__(self):
        self.client = None
        self.db = None
        self.collection = None
        self.taxonomy_map = {}  # keyword -> domain mapping
        self.batch_size = 100
        self.logger = setup_logging("domain_reassignment")
        self.logger.info("🔌 Connecting to MongoDB with shared helper...")
        self.client, self.db, self.collection = connect_to_database()
        try:
            self.client.admin.command('ping')
            self.logger.info("✅ Connected to MongoDB successfully!")
            collection_count = self.collection.count_documents({})
            self.logger.info(f"📊 Found {collection_count} documents in articles collection")
        except Exception as e:
            self.logger.error(f"❌ Failed to verify MongoDB connection: {e}")
            sys.exit(1)
        self.load_taxonomy()
    
    def load_taxonomy(self):
        """Load taxonomy mappings from base and custom taxonomy files"""
        self.logger.info("📚 Loading taxonomy mappings...")
        
        try:
            # Load base taxonomy
            base_taxonomy_path = BACKEND_ROOT / "src" / "main" / "resources" / "taxonomy" / "base-taxonomy.json"
            if base_taxonomy_path.exists():
                with open(base_taxonomy_path, 'r', encoding='utf-8') as f:
                    base_taxonomy = json.load(f)
                    self._process_taxonomy(base_taxonomy, "base")
            else:
                self.logger.warning(f"⚠️ Base taxonomy file not found: {base_taxonomy_path}")
            
            # Load custom taxonomy
            custom_taxonomy_path = BACKEND_ROOT / "taxonomy-custom.json"
            if custom_taxonomy_path.exists():
                with open(custom_taxonomy_path, 'r', encoding='utf-8') as f:
                    custom_taxonomy = json.load(f)
                    self._process_taxonomy(custom_taxonomy, "custom")
            else:
                self.logger.warning(f"⚠️ Custom taxonomy file not found: {custom_taxonomy_path}")
            
            self.logger.info(f"📊 Loaded {len(self.taxonomy_map)} keyword-to-domain mappings")
            
            # Log some sample mappings for verification
            sample_mappings = list(self.taxonomy_map.items())[:5]
            for keyword, domain in sample_mappings:
                self.logger.debug(f"   '{keyword}' → '{domain}'")
                
        except Exception as e:
            self.logger.error(f"❌ Failed to load taxonomy: {e}")
            sys.exit(1)
    
    def _process_taxonomy(self, taxonomy_data: Dict, source: str):
        """Process taxonomy data and build keyword-to-domain mapping"""
        processed_count = 0
        
        for domain, keywords in taxonomy_data.items():
            # Skip metadata entries
            if domain.startswith('_') or not isinstance(keywords, list):
                continue
            
            for keyword in keywords:
                if isinstance(keyword, str) and keyword.strip():
                    # Store case-insensitive mapping
                    keyword_lower = keyword.strip().lower()
                    self.taxonomy_map[keyword_lower] = domain
                    processed_count += 1
        
        self.logger.info(f"📖 Processed {processed_count} keywords from {source} taxonomy")
    
    def find_articles_without_domains(self) -> List[Dict]:
        """Find articles with zero domains assigned (empty or null tags array)"""
        try:
            self.logger.info("🔍 Finding articles without domains...")
            
            # Query for articles with empty or null tags array (tags = user-facing interest buckets)
            query = {
                "$or": [
                    {"tags": {"$exists": False}},
                    {"tags": None},
                    {"tags": []},
                    {"tags": {"$size": 0}}
                ]
            }
            
            # Project only necessary fields for efficiency
            projection = {
                "_id": 1,
                "title": 1,
                "domainCategories": 1,  # Fine-grained keywords for mapping
                "tags": 1,              # User-facing interest buckets (what we want to populate)
                "publishDate": 1,
                "source": 1
            }
            
            articles = list(self.collection.find(query, projection))
            
            self.logger.info(f"📊 Found {len(articles)} articles without domain tags")
            
            # Log sample of what we found
            if articles:
                sample = articles[0]
                self.logger.info(f"📝 Sample article structure:")
                self.logger.info(f"   Title: {sample.get('title', 'N/A')[:50]}...")
                self.logger.info(f"   Tags: {sample.get('tags', 'N/A')}")
                self.logger.info(f"   DomainCategories: {sample.get('domainCategories', 'N/A')}")
            
            return articles
            
        except Exception as e:
            self.logger.error(f"❌ Failed to query articles: {e}")
            return []
    
    def map_keywords_to_domains(self, keywords: List[str]) -> Set[str]:
        """Map a list of keywords to their corresponding domains"""
        if not keywords:
            return set()
        
        domains = set()
        
        for keyword in keywords:
            if not isinstance(keyword, str) or not keyword.strip():
                continue
            
            keyword_lower = keyword.strip().lower()
            
            # Direct lookup
            if keyword_lower in self.taxonomy_map:
                domains.add(self.taxonomy_map[keyword_lower])
                continue
            
            # Partial matching for compound terms
            for taxonomy_keyword, domain in self.taxonomy_map.items():
                # Bidirectional partial matching
                if (taxonomy_keyword in keyword_lower or keyword_lower in taxonomy_keyword):
                    # Ensure meaningful match (not just single characters)
                    if min(len(taxonomy_keyword), len(keyword_lower)) >= 3:
                        domains.add(domain)
                        break
        
        return domains
    
    def process_articles_batch(self, articles: List[Dict]) -> Dict[str, int]:
        """Process a batch of articles and assign domains based on keyword tags"""
        stats = {
            "processed": 0,
            "updated": 0,
            "skipped": 0,
            "errors": 0
        }
        
        for article in articles:
            try:
                stats["processed"] += 1
                article_id = article["_id"]
                title = article.get("title", "Unknown")
                keywords = article.get("domainCategories", [])
                
                # Skip if no keywords available for mapping
                if not keywords:
                    stats["skipped"] += 1
                    self.logger.debug(f"⏭️ Skipping article '{self._truncate_title(title)}' - no domainCategories keywords")
                    continue
                
                # Map domainCategories keywords to user-facing domain tags
                mapped_domains = self.map_keywords_to_domains(keywords)
                
                if mapped_domains:
                    # Update article with new domain tags (user-facing interest buckets)
                    domain_list = sorted(list(mapped_domains))  # Sort for consistency
                    update_result = self.collection.update_one(
                        {"_id": article_id},
                        {"$set": {"tags": domain_list}}
                    )
                    
                    if update_result.modified_count > 0:
                        stats["updated"] += 1
                        self.logger.info(f"✅ Updated '{self._truncate_title(title)}': {len(keywords)} keywords → {len(domain_list)} domain tags")
                        self.logger.debug(f"   Source Keywords: {keywords[:3]}{'...' if len(keywords) > 3 else ''}")
                        self.logger.debug(f"   Mapped Domain Tags: {domain_list}")
                    else:
                        stats["skipped"] += 1
                        self.logger.debug(f"⏭️ No update needed for '{self._truncate_title(title)}'")
                else:
                    stats["skipped"] += 1
                    self.logger.debug(f"⏭️ No domain mapping found for '{self._truncate_title(title)}' with keywords: {keywords[:3] if keywords else 'None'}")
                
            except Exception as e:
                stats["errors"] += 1
                self.logger.error(f"❌ Error processing article '{self._truncate_title(title)}': {e}")
        
        return stats
    
    def _truncate_title(self, title: str, max_length: int = 50) -> str:
        """Truncate title for logging"""
        if not title:
            return "Unknown"
        return title[:max_length] + "..." if len(title) > max_length else title
    
    def run(self):
        """Main execution method"""
        start_time = datetime.now()
        self.logger.info("🎯 Starting domain reassignment process...")
        
        try:
            # Find articles without domains
            articles = self.find_articles_without_domains()
            
            if not articles:
                self.logger.info("✨ No articles found without domains. All articles are properly categorized!")
                return
            
            # Process articles in batches
            total_stats = {
                "processed": 0,
                "updated": 0,
                "skipped": 0,
                "errors": 0
            }
            
            batch_count = 0
            for i in range(0, len(articles), self.batch_size):
                batch = articles[i:i + self.batch_size]
                batch_count += 1
                
                self.logger.info(f"📦 Processing batch {batch_count} ({len(batch)} articles)...")
                batch_stats = self.process_articles_batch(batch)
                
                # Aggregate statistics
                for key in total_stats:
                    total_stats[key] += batch_stats[key]
                
                # Log batch progress
                self.logger.info(f"📊 Batch {batch_count} complete: {batch_stats['updated']} updated, {batch_stats['skipped']} skipped, {batch_stats['errors']} errors")
                
                # Small delay between batches to avoid overwhelming the database
                if i + self.batch_size < len(articles):
                    time.sleep(0.1)
            
            # Final statistics
            duration = datetime.now() - start_time
            self.logger.info("🎉 Domain reassignment completed!")
            self.logger.info(f"📊 FINAL STATISTICS:")
            self.logger.info(f"   📈 Total Articles Processed: {total_stats['processed']}")
            self.logger.info(f"   ✅ Articles Updated: {total_stats['updated']}")
            self.logger.info(f"   ⏭️ Articles Skipped: {total_stats['skipped']}")
            self.logger.info(f"   ❌ Errors: {total_stats['errors']}")
            self.logger.info(f"   ⏱️ Duration: {duration}")
            
            if total_stats['updated'] > 0:
                self.logger.info(f"🎯 Success Rate: {(total_stats['updated'] / total_stats['processed']) * 100:.1f}%")
            
        except Exception as e:
            self.logger.error(f"💥 Fatal error during execution: {e}")
            sys.exit(1)
        
        finally:
            if self.client:
                self.client.close()
                self.logger.info("🔌 Database connection closed")

def main():
    """Main entry point"""
    try:
        script = DomainReassignmentScript()
        script.run()
    except KeyboardInterrupt:
        print("\n⚠️ Script interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"💥 Unexpected error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()