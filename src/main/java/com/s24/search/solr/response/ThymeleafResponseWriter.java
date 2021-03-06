package com.s24.search.solr.response;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import nz.net.ultraq.thymeleaf.LayoutDialect;

import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.BinaryResponseWriter;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.AbstractContext;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import com.google.common.base.Preconditions;

/**
 * A response writer utilizing the Thymeleaf template engine.
 * 
 * @author Shopping24 GmbH, Torsten Bøgh Köster (@tboeghk)
 */
public class ThymeleafResponseWriter implements QueryResponseWriter, SolrCoreAware {

   private TemplateEngine templateEngine;
   private FileTemplateResolver templateResolver;
   private LayoutDialect layoutDialect;
   private Locale locale = Locale.getDefault();
   private SolrParams configuration;
   private SolrCore core;

   /**
    * This get's called as soon the core is ready, which is after the
    * {@linkplain #init(NamedList)} method below.
    */
   @Override
   public void inform(SolrCore core) {
      this.core = core;
   }

   /**
    * Configures engine and template resolving.
    */
   @Override
   public void init(@SuppressWarnings("rawtypes") NamedList args) {
      Preconditions.checkNotNull(args);
      this.configuration = SolrParams.toSolrParams(args);

      // configure template resolver
      templateResolver = new FileTemplateResolver();
      templateResolver.setCharacterEncoding("utf-8");
      templateResolver.setTemplateMode(configuration.get("tl.templateMode", "XHTML"));
      templateResolver.setSuffix(configuration.get("tl.suffix", ".html"));

      if (configuration.get("tl.cacheTtlMs") != null) {
         templateResolver.setCacheTTLMs(Long.parseLong(configuration.get("tl.cacheTTLMs")));
      } else {
         templateResolver.setCacheTTLMs(TimeUnit.HOURS.toMillis(1l));
      }

      if (configuration.get("tl.locale") != null) {
         locale = Locale.forLanguageTag(configuration.get("tl.locale"));
      }

      layoutDialect = new LayoutDialect();
   }

   /**
    * 
    * @return
    */
   protected TemplateEngine getEngine() {
      // create engine
      if (templateEngine == null) {

         // use solr core here
         templateResolver.setPrefix(configuration.get("tl.prefix",
               core.getSolrConfig().getResourceLoader().getConfigDir()
                     + "/templates/"));

         TemplateEngine te = new TemplateEngine();
         te.setTemplateResolver(templateResolver);
         te.addDialect(layoutDialect);

         // check race condition, avoid synchronized block
         if (templateEngine == null) {
            templateEngine = te;
         }
      }

      return templateEngine;
   }

   @Override
   public void write(Writer writer, SolrQueryRequest request, SolrQueryResponse response) throws IOException {
      Preconditions.checkNotNull(writer);
      Preconditions.checkNotNull(request);
      Preconditions.checkNotNull(response);

      // get template name from request params
      String templateName = request.getParams().get("tl.template");
      Preconditions.checkNotNull(templateName, "No tl.template given");

      AbstractContext context = null;

      // requestDispatcher/requestParsers/@addHttpRequestToContext is enabled,
      // use webcontext
      if (request.getContext().containsKey("httpRequest")) {
         HttpServletRequest httpServletRequest = (HttpServletRequest) request.getContext().get("httpRequest");
         context = new WebContext(
               httpServletRequest, 
               null, 
               httpServletRequest.getServletContext(), 
               locale);
      } else {
         // if the http request does not reside inside the solr context, proceed
         // with best-effort
         context = new Context(locale);
      }

      // Prefill context with some defaults
      context.setVariable("request", request);
      context.setVariable("params", SolrParams.toMap(request.getParams().toNamedList()));

      // add core properties
      Properties coreProperties = request.getCore().getResourceLoader().getCoreProperties();
      if (coreProperties != null) {
         for (Entry<Object, Object> p : coreProperties.entrySet()) {
            context.setVariable(p.getKey().toString().replace('.', '_'), p.getValue());
         }
      }

      SolrResponse rsp = new QueryResponse();
      NamedList<Object> parsedResponse = BinaryResponseWriter.getParsedResponse(request, response);
      rsp.setResponse(parsedResponse);
      context.setVariable("response", rsp);

      getEngine().process(templateName, context, writer);
   }

   @Override
   public String getContentType(SolrQueryRequest request, SolrQueryResponse response) {
      Preconditions.checkNotNull(request);

      return request.getParams().get("tl.contentType", "text/html;charset=UTF-8");
   }

}
