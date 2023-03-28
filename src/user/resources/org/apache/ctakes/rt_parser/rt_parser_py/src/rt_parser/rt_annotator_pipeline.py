# Should accept cmd line parameters such as: hostname, port, queue name for recieving cas, and queue name for
# sending cas


# These are the lines that ignore the typesystem errors
import warnings

from ctakes_pbj.component.pbj_receiver import start_receiver
from rt_parser.rt_annotator import RTAnnotator
from ctakes_pbj.component.pbj_sender import PBJSender
from ctakes_pbj.pipeline.pbj_pipeline import PBJPipeline

warnings.filterwarnings("ignore")

def main():
    pipeline = PBJPipeline()
    pipeline.add(RTAnnotator())
    pipeline.add(PBJSender())
    pipeline.initialize()
    start_receiver(pipeline)


main()
