/**
 * 
 */
package mblog.core.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import mblog.core.lang.Consts;
import mblog.core.persist.dao.AttachDao;
import mblog.core.persist.dao.PostDao;
import mblog.core.persist.dao.UserDao;
import mblog.core.persist.entity.PostPO;
import mblog.core.pojos.Attach;
import mblog.core.pojos.Post;
import mblog.core.pojos.Tag;
import mblog.core.pojos.User;
import mblog.core.service.AttachService;
import mblog.core.service.PostService;
import mblog.core.service.TagService;
import mtons.commons.lang.EntityStatus;
import mtons.commons.pojos.Paging;
import mtons.commons.pojos.UserContextHolder;
import mtons.commons.pojos.UserProfile;
import mtons.commons.utils.PreviewHtmlUtils;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.hibernate.search.FullTextQuery;
import org.hibernate.search.FullTextSession;
import org.hibernate.search.Search;
import org.hibernate.search.SearchFactory;
import org.hibernate.search.query.dsl.QueryBuilder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * @author langhsu
 *
 */
public class PostServiceImpl implements PostService {
	@Autowired
	private PostDao postDao;
	@Autowired
	private AttachDao attachDao;
	@Autowired
	private UserDao userDao;
	@Autowired
	private AttachService attachService;
	@Autowired
	private TagService tagService;
	
	private static String[] IGNORE = new String[]{"author", "snapshot"};
	private static String[] IGNORE_LIST = new String[]{"author", "snapshot", "content"};
	
	@Override
	@Transactional(readOnly = true)
	public void paging(Paging paging) {
		List<PostPO> list = postDao.paging(paging);
		List<Post> rets = new ArrayList<Post>();
		for (PostPO po : list) {
			rets.add(toVo(po, 0));
		}
		paging.setResults(rets);
	}
	
	@Override
	@Transactional(readOnly = true)
	public void pagingByUserId(Paging paging, long userId) {
		List<PostPO> list = postDao.pagingByUserId(paging, userId);
		List<Post> rets = new ArrayList<Post>();
		for (PostPO po : list) {
			rets.add(toVo(po ,0));
		}
		paging.setResults(rets);
	}
	
	@Override
	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public List<Post> search(Paging paging, String q) throws InterruptedException, IOException, InvalidTokenOffsetsException {
		FullTextSession fullTextSession = Search.getFullTextSession(postDao.getSession());
//	    fullTextSession.createIndexer().startAndWait();
	    SearchFactory sf = fullTextSession.getSearchFactory();
	    QueryBuilder qb = sf.buildQueryBuilder().forEntity(PostPO.class).get();
	    org.apache.lucene.search.Query luceneQuery  = qb.keyword().onFields("title","summary","tags").matching(q).createQuery();
	    FullTextQuery query = fullTextSession.createFullTextQuery(luceneQuery);
	    query.setFirstResult(paging.getFirstResult());
	    query.setMaxResults(paging.getMaxResults());
	   
	    StandardAnalyzer standardAnalyzer = new StandardAnalyzer(); 
	    SimpleHTMLFormatter formatter = new SimpleHTMLFormatter("<span style='color:red;'>", "</span>");
        QueryScorer queryScorer = new QueryScorer(luceneQuery);
        Highlighter highlighter = new Highlighter(formatter, queryScorer);
        
	    List<PostPO> list = query.list();
	    int resultSize = query.getResultSize();
	    
	    List<Post> rets = new ArrayList<Post>();
		for (PostPO po : list) {
			Post m = toVo(po ,0);
			String title = highlighter.getBestFragment(standardAnalyzer, "title", m.getTitle());
			String summary = highlighter.getBestFragment(standardAnalyzer, "summary", m.getSummary());
			String tags = highlighter.getBestFragment(standardAnalyzer, "tags", m.getTags());
			if (StringUtils.isNotEmpty(title)) {
				m.setTitle(title);
			}
			if (StringUtils.isNotEmpty(summary)) {
				m.setSummary(summary);
			}
			if (StringUtils.isNotEmpty(tags)) {
				m.setTags(tags);
			}
			rets.add(m);
		}
		paging.setTotalCount(resultSize);
		paging.setResults(rets);
		return rets;
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<Post> recents(int maxResutls, long ignoreUserId) {
		List<PostPO> list = postDao.recents(maxResutls, ignoreUserId);
		List<Post> rets = new ArrayList<Post>();
		for (PostPO po : list) {
			rets.add(toVo(po, 0));
		}
		return rets;
	}
	
	@Override
	@Transactional
	public void post(Post post) {
		PostPO po = postDao.get(post.getId());
		if (po != null) {
			po.setUpdated(new Date());
			po.setTitle(post.getTitle());
			po.setContent(post.getContent());
			po.setSummary(trimSummary(post.getContent()));
			po.setTags(post.getTags());
		} else {
			po = new PostPO();
			UserProfile up = UserContextHolder.getUserProfile();
			
			po.setAuthor(userDao.get(up.getId()));
			po.setCreated(new Date());
			po.setStatus(EntityStatus.ENABLED);
			
			// content
			po.setType(post.getType());
			po.setTitle(post.getTitle());
			po.setContent(post.getContent());
			po.setSummary(trimSummary(post.getContent())); // summary handle
			po.setTags(post.getTags());
			
			postDao.save(po);
		}
		
		// attach handle
		if (post.getAlbums() != null) {
			for (int i = 0; i < post.getAlbums().size(); i++) {
				Attach a = post.getAlbums().get(i);
				a.setToId(po.getId());
				long id = attachService.add(a);
				if (i == 0) {
					po.setSnapshot(attachDao.get(id));
				}
			}
		}
		
		// tag handle
		if (StringUtils.isNotBlank(post.getTags())) {
			List<Tag> tags = new ArrayList<Tag>();
			String[] ts = StringUtils.split(post.getTags(), Consts.SEPARATOR);
			
			for (String t : ts) {
				Tag tag = new Tag();
				tag.setName(t);
				tag.setLastPostId(po.getId());
				tag.setPosts(1);
				tags.add(tag);
			}
			
			tagService.batchPost(tags);
		}
	}
	
	@Override
	@Transactional
	public Post get(long id) {
		PostPO po = postDao.get(id);
		Post d = null;
		if (po != null) {
			d = toVo(po, 1);
		}
		List<Attach> albs = attachService.list(d.getId());
		d.setAlbums(albs);
		return d;
	}
	
	@Override
	@Transactional
	public void delete(long id) {
		UserProfile up = UserContextHolder.getUserProfile();
		
		Assert.notNull(up, "用户认证失败, 请重新登录!");
		
		PostPO po = postDao.get(id);
		if (po != null) {
			Assert.isTrue(po.getAuthor().getId() == up.getId(), "认证失败");
			attachService.deleteByToId(id);
			postDao.delete(po);
		}
	}
	
	@Override
	@Transactional
	public void updateView(long id) {
		PostPO po = postDao.get(id);
		if (po != null) {
			po.setViews(po.getViews() + 1);
		}
	}

	@Override
	@Transactional
	public void updateHeart(long id) {
		PostPO po = postDao.get(id);
		if (po != null) {
			po.setHearts(po.getHearts() + 1);
		}
	}
	
	private Post toVo(PostPO po, int level) {
		Post d = new Post();
		if (level > 0) {
			BeanUtils.copyProperties(po, d, IGNORE);
		} else {
			BeanUtils.copyProperties(po, d, IGNORE_LIST);
		}
		
		if (po.getAuthor() != null) {
			User u = new User();
			u.setId(po.getAuthor().getId());
			u.setUsername(po.getAuthor().getUsername());
			u.setName(po.getAuthor().getName());
			u.setAvater(po.getAuthor().getAvater());
			d.setAuthor(u);
		}
		if (po.getSnapshot() != null) {
			Attach a = new Attach();
			BeanUtils.copyProperties(po.getSnapshot(), a);
			d.setSnapshot(a);
		}
		return d;
	}
	
	/**
     * 截取文章内容
     * @param text
     * @return
     */
    private String trimSummary(String text){
        return PreviewHtmlUtils.truncateHTML(text, 126);
    }

}